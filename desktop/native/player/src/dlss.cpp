#include "dlss.h"

#include <windows.h>
// Après windows.h.
#include <GL/gl.h>
#include <d3d11.h>
#include <dxgi.h>
#include <softpub.h>
#include <urlmon.h>
#include <wintrust.h>

#include <algorithm>
#include <cstring>
#include <vector>

#include "util.h"

// ---------------------------------------------------------------------------
// NGX (SDK DLSS de NVIDIA) : constantes et fonctions du cœur du pilote utilisées ici
// ---------------------------------------------------------------------------

// nvngx_dlss.dll de la version 310.9.16 du SDK (dépôt public de NVIDIA), signé par NVIDIA.
static const wchar_t kDllUrl[] = L"https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.16/lib/Windows_x86_64/rel/nvngx_dlss.dll";
static const char kProjectId[] = "5a1d3c2e-7b4f-4e0a-9c61-2f8e0d4b7a93";  // RomCloud (moteur « personnalisé »)

typedef unsigned NgxResult;
static bool ngxFailed(NgxResult r) { return (r & 0xFFF00000) == 0xBAD00000; }
constexpr NgxResult kNgxSuccess = 0x1;
constexpr unsigned kNgxVersion = 0x15;           // NVSDK_NGX_Version_API
constexpr int kNgxEngineCustom = 0;              // NVSDK_NGX_ENGINE_TYPE_CUSTOM
constexpr int kNgxSuperSampling = 1;             // NVSDK_NGX_Feature_SuperSampling
constexpr int kDlssMvLowRes = 1 << 1, kDlssAutoExposure = 1 << 6;  // NVSDK_NGX_DLSS_Feature_Flags_*
enum { kPerfMax = 0, kPerfBalanced = 1, kPerfQuality = 2, kPerfUltra = 3, kPerfDlaa = 5 };  // NVSDK_NGX_PerfQuality_Value_*

// NVSDK_NGX_FeatureCommonInfo : dossiers où chercher nvngx_dlss.dll.
struct NgxFeatureInfo {
  const wchar_t* const* paths;
  unsigned pathCount;
  void* internal;
  void* logCallback;
  int logLevel;
  bool disableOtherSinks;
};

// Paramètres NGX : objet C++ du pilote, compilé par MSVC. Ses fonctions virtuelles surchargées y sont
// rangées par groupe, dans l'ordre inverse de leur déclaration (Set puis Get : void*, ID3D12Resource*,
// ID3D11Resource*, int, unsigned, double, float, unsigned long long), puis Reset ; même convention
// d'appel x64 que MinGW (this dans rcx).
struct NgxParameterTable {
  void (*setVoid)(NgxParameters*, const char*, void*);
  void (*setD3D12)(NgxParameters*, const char*, void*);
  void (*setD3D11)(NgxParameters*, const char*, ID3D11Resource*);
  void (*setInt)(NgxParameters*, const char*, int);
  void (*setUnsigned)(NgxParameters*, const char*, unsigned);
  void (*setDouble)(NgxParameters*, const char*, double);
  void (*setFloat)(NgxParameters*, const char*, float);
  void (*setULL)(NgxParameters*, const char*, unsigned long long);
  NgxResult (*getVoid)(NgxParameters*, const char*, void**);
  NgxResult (*getD3D12)(NgxParameters*, const char*, void**);
  NgxResult (*getD3D11)(NgxParameters*, const char*, ID3D11Resource**);
  NgxResult (*getInt)(NgxParameters*, const char*, int*);
};
struct NgxParameters {
  const NgxParameterTable* table;
};

typedef NgxResult (*PfnInit)(const char*, int, const char*, const wchar_t*, ID3D11Device*, unsigned, const NgxFeatureInfo*);
typedef NgxResult (*PfnShutdown)(ID3D11Device*, unsigned*);  // périphériques restants
typedef NgxResult (*PfnParameters)(NgxParameters**);
typedef NgxResult (*PfnDestroyParameters)(NgxParameters*);
typedef NgxResult (*PfnCreate)(ID3D11DeviceContext*, int, NgxParameters*, NgxHandle**);
typedef NgxResult (*PfnEvaluate)(ID3D11DeviceContext*, const NgxHandle*, const NgxParameters*, void*);
typedef NgxResult (*PfnRelease)(NgxHandle*);
static PfnInit ngxInit;
static PfnShutdown ngxShutdown;
static PfnParameters ngxCapabilities, ngxAllocate;
static PfnDestroyParameters ngxDestroyParameters;
static PfnCreate ngxCreate;
static PfnEvaluate ngxEvaluate;
static PfnRelease ngxRelease;

// WGL_NV_DX_interop2 : textures Direct3D 11 vues comme des textures OpenGL.
typedef HANDLE(WINAPI* PfnOpenDevice)(void*);
typedef BOOL(WINAPI* PfnCloseDevice)(HANDLE);
typedef HANDLE(WINAPI* PfnRegister)(HANDLE, void*, GLuint, GLenum, GLenum);
typedef BOOL(WINAPI* PfnUnregister)(HANDLE, HANDLE);
typedef BOOL(WINAPI* PfnLock)(HANDLE, GLint, HANDLE*);
static PfnOpenDevice wglDXOpenDevice;
static PfnCloseDevice wglDXCloseDevice;
static PfnRegister wglDXRegisterObject;
static PfnUnregister wglDXUnregisterObject;
static PfnLock wglDXLockObjects, wglDXUnlockObjects;
constexpr GLenum kAccessReadOnly = 0x0000, kAccessWriteDiscard = 0x0002;

template <typename T>
static bool symbol(HMODULE module, T& out, const char* name) {
  out = reinterpret_cast<T>(reinterpret_cast<void*>(GetProcAddress(module, name)));
  return out != nullptr;
}

template <typename T>
static bool wglSymbol(T& out, const char* name) {
  out = reinterpret_cast<T>(reinterpret_cast<void*>(wglGetProcAddress(name)));
  return out != nullptr;
}

/** _nvngx.dll du pilote : dossier du pilote Direct3D 11 de NVIDIA chargé, sinon celui du registre. */
static HMODULE loadCore() {
  wchar_t path[MAX_PATH] = {};
  HMODULE umd = GetModuleHandleW(L"nvwgf2umx.dll");
  if (umd && GetModuleFileNameW(umd, path, MAX_PATH)) {
    if (wchar_t* slash = wcsrchr(path, L'\\')) {
      wcscpy(slash, L"\\_nvngx.dll");
      if (HMODULE core = LoadLibraryW(path)) return core;
    }
  }
  DWORD size = sizeof(path);
  if (RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\NVIDIA Corporation\\Global\\NGXCore", L"FullPath", RRF_RT_REG_SZ, nullptr, path,
                   &size) == ERROR_SUCCESS) {
    wcscat(path, L"\\_nvngx.dll");
    return LoadLibraryW(path);
  }
  return nullptr;
}

/** Signature Authenticode valide (fichier téléchargé). */
static bool signatureValid(const std::wstring& path) {
  WINTRUST_FILE_INFO file = {};
  file.cbStruct = sizeof(file);
  file.pcwszFilePath = path.c_str();
  WINTRUST_DATA data = {};
  data.cbStruct = sizeof(data);
  data.dwUIChoice = WTD_UI_NONE;
  data.fdwRevocationChecks = WTD_REVOKE_NONE;
  data.dwUnionChoice = WTD_CHOICE_FILE;
  data.pFile = &file;
  data.dwStateAction = WTD_STATEACTION_VERIFY;
  GUID action = WINTRUST_ACTION_GENERIC_VERIFY_V2;
  const LONG status = WinVerifyTrust(nullptr, &action, &data);
  data.dwStateAction = WTD_STATEACTION_CLOSE;
  WinVerifyTrust(nullptr, &action, &data);
  return status == ERROR_SUCCESS;
}

// ---------------------------------------------------------------------------

Dlss::~Dlss() {
  if (download_.joinable()) download_.join();
}

void Dlss::fail(const char* message, const char* reason) {
  logf("DLSS impossible : %s", reason);
  state_ = State::Failed;
  message_ = message;
}

std::string Dlss::takeMessage() {
  std::string message;
  message.swap(message_);
  return message;
}

Dlss::State Dlss::prepare() {
  if (state_ == State::Off) {
    if (!start()) return state_;
    const std::string dll = joinPath(dir_, "nvngx_dlss.dll");
    if (fileExists(dll)) {
      if (initNgx()) {
        state_ = State::Ready;
        message_ = "dlss_ready";
      }
      return state_;
    }
    // Premier usage : nvngx_dlss.dll (59 Mo) téléchargé en arrière-plan, FSR affiché en attendant.
    state_ = State::Downloading;
    message_ = "dlss_downloading";
    makeDirs(dir_);
    const std::wstring target = widen(dll), partial = target + L".part";
    download_ = std::thread([this, target, partial] {
      HRESULT hr = URLDownloadToFileW(nullptr, kDllUrl, partial.c_str(), 0, nullptr);
      bool ok = SUCCEEDED(hr) && signatureValid(partial) && MoveFileExW(partial.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING);
      if (!ok) {
        logf("DLSS : téléchargement de nvngx_dlss.dll impossible (0x%08lx)", (unsigned long)hr);
        DeleteFileW(partial.c_str());
      }
      downloaded_ = ok ? 1 : -1;
    });
  }
  if (state_ == State::Downloading && downloaded_ != 0) {
    download_.join();
    if (downloaded_ < 0) {
      fail("dlss_download_failed", "téléchargement");
    } else if (initNgx()) {
      state_ = State::Ready;
      message_ = "dlss_ready";
    }
  }
  return state_;
}

/** Carte NVIDIA, Direct3D 11, cœur NGX et partage avec OpenGL (avant tout téléchargement). */
bool Dlss::start() {
  if (!wglSymbol(wglDXOpenDevice, "wglDXOpenDeviceNV") || !wglSymbol(wglDXCloseDevice, "wglDXCloseDeviceNV") ||
      !wglSymbol(wglDXRegisterObject, "wglDXRegisterObjectNV") || !wglSymbol(wglDXUnregisterObject, "wglDXUnregisterObjectNV") ||
      !wglSymbol(wglDXLockObjects, "wglDXLockObjectsNV") || !wglSymbol(wglDXUnlockObjects, "wglDXUnlockObjectsNV")) {
    fail("dlss_unsupported", "OpenGL sans WGL_NV_DX_interop2 (pas une carte NVIDIA ?)");
    return false;
  }
  // Première carte NVIDIA RTX (celle de l'affichage OpenGL sur un PC à une carte).
  IDXGIFactory1* factory = nullptr;
  if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), reinterpret_cast<void**>(&factory)))) {
    fail("dlss_unsupported", "DXGI");
    return false;
  }
  IDXGIAdapter1* adapter = nullptr;
  for (UINT i = 0; !adapter; i++) {
    IDXGIAdapter1* candidate = nullptr;
    if (factory->EnumAdapters1(i, &candidate) != S_OK) break;
    DXGI_ADAPTER_DESC1 desc;
    // DLSS : cœurs Tensor des cartes RTX (GeForce RTX, RTX Axxx…) ; pas de téléchargement pour les autres.
    if (SUCCEEDED(candidate->GetDesc1(&desc)) && desc.VendorId == 0x10DE && wcsstr(desc.Description, L"RTX")) {
      adapter = candidate;
      logf("DLSS : carte %s", narrow(desc.Description).c_str());
    } else {
      candidate->Release();
    }
  }
  factory->Release();
  if (!adapter) {
    fail("dlss_unsupported", "pas de carte NVIDIA RTX");
    return false;
  }
  const HRESULT hr = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &device_, nullptr, &context_);
  adapter->Release();
  if (FAILED(hr)) {
    fail("dlss_unsupported", "Direct3D 11");
    return false;
  }
  HMODULE core = loadCore();
  core_ = core;
  if (!core || !symbol(core, ngxInit, "NVSDK_NGX_D3D11_Init_ProjectID") || !symbol(core, ngxShutdown, "NVSDK_NGX_D3D11_Shutdown1") ||
      !symbol(core, ngxCapabilities, "NVSDK_NGX_D3D11_GetCapabilityParameters") ||
      !symbol(core, ngxAllocate, "NVSDK_NGX_D3D11_AllocateParameters") ||
      !symbol(core, ngxDestroyParameters, "NVSDK_NGX_D3D11_DestroyParameters") || !symbol(core, ngxCreate, "NVSDK_NGX_D3D11_CreateFeature") ||
      !symbol(core, ngxEvaluate, "NVSDK_NGX_D3D11_EvaluateFeature") || !symbol(core, ngxRelease, "NVSDK_NGX_D3D11_ReleaseFeature")) {
    fail("dlss_unsupported", "cœur NGX du pilote introuvable");
    return false;
  }
  interop_ = wglDXOpenDevice(device_);
  if (!interop_) {
    fail("dlss_unsupported", "partage Direct3D 11 / OpenGL refusé");
    return false;
  }
  return true;
}

bool Dlss::initNgx() {
  const std::wstring dir = widen(dir_);
  const wchar_t* paths[] = {dir.c_str()};
  NgxFeatureInfo info = {paths, 1, nullptr, nullptr, 0, false};
  NgxResult r = ngxInit(kProjectId, kNgxEngineCustom, "1.0", dir.c_str(), device_, kNgxVersion, &info);
  if (ngxFailed(r)) {
    logf("DLSS : NGX refusé (0x%08x)", r);
    fail("dlss_failed", "initialisation de NGX");
    return false;
  }
  ngxReady_ = true;
  NgxParameters* caps = nullptr;
  int available = 0, result = 0;
  if (ngxFailed(ngxCapabilities(&caps)) || !caps) {
    fail("dlss_failed", "capacités de NGX");
    return false;
  }
  caps->table->getInt(caps, "SuperSampling.Available", &available);
  caps->table->getInt(caps, "SuperSampling.FeatureInitResult", &result);
  ngxDestroyParameters(caps);
  if (available != 1) {
    logf("DLSS : non disponible sur cette carte (0x%08x)", (unsigned)result);
    fail("dlss_unsupported", "DLSS non disponible (GeForce RTX requise)");
    return false;
  }
  if (ngxFailed(ngxAllocate(&params_)) || !params_) {
    fail("dlss_failed", "paramètres de NGX");
    return false;
  }
  logf("DLSS prêt");
  return true;
}

static ID3D11Texture2D* makeTexture(ID3D11Device* device, int w, int h, DXGI_FORMAT format, UINT bind, const void* data = nullptr, UINT pitch = 0) {
  D3D11_TEXTURE2D_DESC desc = {};
  desc.Width = (UINT)w;
  desc.Height = (UINT)h;
  desc.MipLevels = 1;
  desc.ArraySize = 1;
  desc.Format = format;
  desc.SampleDesc.Count = 1;
  desc.Usage = D3D11_USAGE_DEFAULT;
  desc.BindFlags = bind;
  D3D11_SUBRESOURCE_DATA init = {data, pitch, 0};
  ID3D11Texture2D* texture = nullptr;
  device->CreateTexture2D(&desc, data ? &init : nullptr, &texture);
  return texture;
}

void Dlss::releaseImages() {
  if (inputObject_) wglDXUnregisterObject(interop_, inputObject_);
  if (outputObject_) wglDXUnregisterObject(interop_, outputObject_);
  inputObject_ = outputObject_ = nullptr;
  GLuint textures[2] = {glInput_, glOutput_};
  if (glInput_) glDeleteTextures(2, textures);
  glInput_ = glOutput_ = 0;
  if (feature_) ngxRelease(feature_);
  feature_ = nullptr;
  for (ID3D11Texture2D** t : {&input_, &output_, &shared_, &motion_, &depth_}) {
    if (*t) (*t)->Release();
    *t = nullptr;
  }
  inW_ = inH_ = outW_ = outH_ = 0;
}

bool Dlss::resize(int inW, int inH, int outW, int outH) {
  if (state_ != State::Ready) return false;
  if (inW == inW_ && inH == inH_ && outW == outW_ && outH == outH_) return feature_ != nullptr;
  // Taille déjà refusée : pas de nouvel essai à chaque image.
  if (inW == refused_[0] && inH == refused_[1] && outW == refused_[2] && outH == refused_[3]) return false;
  releaseImages();
  auto refuse = [&] {
    releaseImages();
    refused_[0] = inW, refused_[1] = inH, refused_[2] = outW, refused_[3] = outH;
    return false;
  };
  inW_ = inW, inH_ = inH, outW_ = outW, outH_ = outH;
  const std::vector<uint32_t> zeros((size_t)inW * inH, 0);
  input_ = makeTexture(device_, inW, inH, DXGI_FORMAT_R8G8B8A8_UNORM, D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE);
  output_ = makeTexture(device_, outW, outH, DXGI_FORMAT_R8G8B8A8_UNORM, D3D11_BIND_UNORDERED_ACCESS | D3D11_BIND_SHADER_RESOURCE);
  shared_ = makeTexture(device_, outW, outH, DXGI_FORMAT_R8G8B8A8_UNORM, D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE);
  motion_ = makeTexture(device_, inW, inH, DXGI_FORMAT_R16G16_FLOAT, D3D11_BIND_SHADER_RESOURCE, zeros.data(), (UINT)inW * 4);
  depth_ = makeTexture(device_, inW, inH, DXGI_FORMAT_R32_FLOAT, D3D11_BIND_SHADER_RESOURCE, zeros.data(), (UINT)inW * 4);
  if (!input_ || !output_ || !shared_ || !motion_ || !depth_) {
    logf("DLSS : images %dx%d -> %dx%d impossibles", inW, inH, outW, outH);
    return refuse();
  }
  GLuint textures[2];
  glGenTextures(2, textures);
  glInput_ = textures[0];
  glOutput_ = textures[1];
  inputObject_ = wglDXRegisterObject(interop_, input_, glInput_, GL_TEXTURE_2D, kAccessWriteDiscard);
  outputObject_ = wglDXRegisterObject(interop_, shared_, glOutput_, GL_TEXTURE_2D, kAccessReadOnly);
  if (!inputObject_ || !outputObject_) {
    logf("DLSS : partage des images avec OpenGL refusé (0x%lx)", GetLastError());
    return refuse();
  }
  // Mode de qualité d'après le rapport d'agrandissement (Ultra Performance : jusqu'à 3x).
  const float ratio = std::max((float)outW / inW, (float)outH / inH);
  const int quality = ratio >= 2.5f ? kPerfUltra : ratio >= 1.9f ? kPerfMax : ratio >= 1.6f ? kPerfBalanced : ratio > 1.1f ? kPerfQuality : kPerfDlaa;
  const NgxParameterTable& t = *params_->table;
  t.setUnsigned(params_, "Width", (unsigned)inW);
  t.setUnsigned(params_, "Height", (unsigned)inH);
  t.setUnsigned(params_, "OutWidth", (unsigned)outW);
  t.setUnsigned(params_, "OutHeight", (unsigned)outH);
  t.setInt(params_, "PerfQualityValue", quality);
  t.setInt(params_, "DLSS.Feature.Create.Flags", kDlssMvLowRes | kDlssAutoExposure);
  t.setInt(params_, "DLSS.Enable.Output.Subrects", 0);
  const NgxResult r = ngxCreate(context_, kNgxSuperSampling, params_, &feature_);
  if (ngxFailed(r) || !feature_) {
    logf("DLSS : agrandissement %dx%d -> %dx%d refusé (0x%08x)", inW, inH, outW, outH, r);
    feature_ = nullptr;
    return refuse();
  }
  logf("DLSS : %dx%d -> %dx%d (mode %d)", inW, inH, outW, outH, quality);
  return true;
}

bool Dlss::lock(bool input) {
  HANDLE object = input ? inputObject_ : outputObject_;
  return object && wglDXLockObjects(interop_, 1, &object);
}

void Dlss::unlock(bool input) {
  HANDLE object = input ? inputObject_ : outputObject_;
  if (object) wglDXUnlockObjects(interop_, 1, &object);
}

bool Dlss::evaluate() {
  if (!feature_) return false;
  const NgxParameterTable& t = *params_->table;
  t.setD3D11(params_, "Color", input_);
  t.setD3D11(params_, "Output", output_);
  t.setD3D11(params_, "Depth", depth_);
  t.setD3D11(params_, "MotionVectors", motion_);
  t.setFloat(params_, "Jitter.Offset.X", 0.0f);
  t.setFloat(params_, "Jitter.Offset.Y", 0.0f);
  t.setFloat(params_, "MV.Scale.X", 1.0f);
  t.setFloat(params_, "MV.Scale.Y", 1.0f);
  t.setInt(params_, "Reset", 1);  // pas d'historique : une seule image (pas de vecteurs de mouvement)
  t.setUnsigned(params_, "DLSS.Render.Subrect.Dimensions.Width", (unsigned)inW_);
  t.setUnsigned(params_, "DLSS.Render.Subrect.Dimensions.Height", (unsigned)inH_);
  t.setFloat(params_, "DLSS.Pre.Exposure", 1.0f);
  t.setFloat(params_, "DLSS.Exposure.Scale", 1.0f);
  const NgxResult r = ngxEvaluate(context_, feature_, params_, nullptr);
  if (ngxFailed(r)) {
    logf("DLSS : agrandissement refusé (0x%08x)", r);
    return false;
  }
  context_->CopyResource(shared_, output_);
  context_->Flush();
  return true;
}

void Dlss::destroy() {
  if (download_.joinable()) download_.join();
  releaseImages();
  if (params_) ngxDestroyParameters(params_);
  params_ = nullptr;
  unsigned devicesLeft = 0;
  if (ngxReady_) ngxShutdown(device_, &devicesLeft);
  ngxReady_ = false;
  if (interop_) wglDXCloseDevice(interop_);
  interop_ = nullptr;
  if (context_) context_->Release();
  if (device_) device_->Release();
  context_ = nullptr;
  device_ = nullptr;
  if (state_ != State::Failed) state_ = State::Off;
}
