#include "netplay.h"

#include <iphlpapi.h>
#include <ws2tcpip.h>

#include <SDL3/SDL.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "core.h"
#include "libretro.h"
#include "util.h"

namespace {

constexpr int kDiscoveryPort = 47321;
constexpr int kVersion = 1;
constexpr uint64_t kTimeoutMs = 120000;  // autre joueur muet : partie arrêtée (menu ouvert compris)
enum : uint8_t { kInput = 1, kState = 2, kCheck = 3, kResync = 4, kBye = 5, kPacket = 6 };

void put32(uint8_t* p, uint32_t v) {
  p[0] = (uint8_t)(v >> 24);
  p[1] = (uint8_t)(v >> 16);
  p[2] = (uint8_t)(v >> 8);
  p[3] = (uint8_t)v;
}

uint32_t get32(const uint8_t* p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }

uint32_t fnv(const uint8_t* data, size_t size) {
  uint32_t h = 2166136261u;
  for (size_t i = 0; i < size; i++) h = (h ^ data[i]) * 16777619u;
  return h;
}

/** UTF-8 -> UTF-8 modifié de Java (writeUTF) : caractères hors du plan de base en paires de substitution, NUL sur 2 octets. */
std::string toModifiedUtf8(const std::string& s) {
  std::string out;
  for (uint32_t cp : utf8Decode(s)) {
    auto put3 = [&](uint32_t c) {
      out += (char)(0xE0 | (c >> 12));
      out += (char)(0x80 | ((c >> 6) & 0x3F));
      out += (char)(0x80 | (c & 0x3F));
    };
    if (cp == 0) {
      out += (char)0xC0;
      out += (char)0x80;
    } else if (cp < 0x80) {
      out += (char)cp;
    } else if (cp < 0x800) {
      out += (char)(0xC0 | (cp >> 6));
      out += (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      put3(cp);
    } else {
      cp -= 0x10000;
      put3(0xD800 | (cp >> 10));
      put3(0xDC00 | (cp & 0x3FF));
    }
  }
  return out;
}

/** UTF-8 modifié de Java (readUTF) -> UTF-8. */
std::string fromModifiedUtf8(const std::string& s) {
  std::string out;
  auto append = [&](uint32_t cp) {
    if (cp < 0x80) {
      out += (char)cp;
    } else if (cp < 0x800) {
      out += (char)(0xC0 | (cp >> 6));
      out += (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out += (char)(0xE0 | (cp >> 12));
      out += (char)(0x80 | ((cp >> 6) & 0x3F));
      out += (char)(0x80 | (cp & 0x3F));
    } else {
      out += (char)(0xF0 | (cp >> 18));
      out += (char)(0x80 | ((cp >> 12) & 0x3F));
      out += (char)(0x80 | ((cp >> 6) & 0x3F));
      out += (char)(0x80 | (cp & 0x3F));
    }
  };
  std::vector<uint32_t> units;
  for (size_t i = 0; i < s.size();) {
    auto c = (uint8_t)s[i];
    if (c < 0x80) {
      units.push_back(c);
      i++;
    } else if ((c & 0xE0) == 0xC0 && i + 1 < s.size()) {
      units.push_back(((c & 0x1F) << 6) | (s[i + 1] & 0x3F));
      i += 2;
    } else if ((c & 0xF0) == 0xE0 && i + 2 < s.size()) {
      units.push_back(((c & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F));
      i += 3;
    } else {
      i++;
    }
  }
  for (size_t i = 0; i < units.size(); i++) {
    uint32_t u = units[i];
    if (u >= 0xD800 && u < 0xDC00 && i + 1 < units.size() && units[i + 1] >= 0xDC00 && units[i + 1] < 0xE000) {
      append(0x10000 + ((u - 0xD800) << 10) + (units[i + 1] - 0xDC00));
      i++;
    } else {
      append(u);
    }
  }
  return out;
}

/** Envoie tout [data] ; faux si la connexion est perdue (connexion non bloquante : attend qu'elle accepte). */
bool sendAll(SOCKET s, const void* data, size_t size) {
  const char* p = static_cast<const char*>(data);
  while (size) {
    int n = send(s, p, (int)std::min<size_t>(size, 1 << 20), 0);
    if (n == SOCKET_ERROR) {
      if (WSAGetLastError() != WSAEWOULDBLOCK) return false;
      WSAPOLLFD pfd{s, POLLWRNORM, 0};
      WSAPoll(&pfd, 1, 100);
      continue;
    }
    p += n;
    size -= (size_t)n;
  }
  return true;
}

/** Lit exactement [size] octets (connexion bloquante, délai de réception de la connexion). */
bool recvAll(SOCKET s, char* data, int size) {
  while (size > 0) {
    int n = recv(s, data, size, 0);
    if (n <= 0) return false;
    data += n;
    size -= n;
  }
  return true;
}

/** Message JSON de writeUTF / readUTF (Java) : longueur sur 2 octets, puis UTF-8 modifié. */
bool writeUtf(SOCKET s, const std::string& text) {
  std::string data = toModifiedUtf8(text);
  if (data.size() > 65535) return false;
  uint8_t head[2] = {(uint8_t)(data.size() >> 8), (uint8_t)data.size()};
  return sendAll(s, head, 2) && sendAll(s, data.data(), data.size());
}

bool readUtf(SOCKET s, std::string& text) {
  uint8_t head[2];
  if (!recvAll(s, reinterpret_cast<char*>(head), 2)) return false;
  std::string data((size_t)((head[0] << 8) | head[1]), '\0');
  if (!data.empty() && !recvAll(s, data.data(), (int)data.size())) return false;
  text = fromModifiedUtf8(data);
  return true;
}

void setTimeout(SOCKET s, DWORD ms) {
  setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&ms), sizeof ms);
}

uint64_t nowMs() {
  return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

/** Adresses de diffusion de chaque réseau IPv4 actif, plus 255.255.255.255. */
std::vector<uint32_t> broadcastAddresses() {
  std::vector<uint32_t> out{INADDR_BROADCAST};
  ULONG size = 16384;
  std::vector<uint8_t> buffer(size);
  auto* adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
  if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, adapters, &size) == ERROR_BUFFER_OVERFLOW) {
    buffer.resize(size);
    adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, adapters, &size) != NO_ERROR) return out;
  }
  for (auto* a = adapters; a; a = a->Next) {
    if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
    for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
      if (u->Address.lpSockaddr->sa_family != AF_INET || u->OnLinkPrefixLength == 0 || u->OnLinkPrefixLength >= 32) continue;
      uint32_t ip = ntohl(reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr)->sin_addr.s_addr);
      uint32_t mask = u->OnLinkPrefixLength ? 0xFFFFFFFFu << (32 - u->OnLinkPrefixLength) : 0;
      uint32_t broadcast = htonl(ip | ~mask);
      if (std::find(out.begin(), out.end(), broadcast) == out.end()) out.push_back(broadcast);
    }
  }
  return out;
}

}  // namespace

std::string jsonEscape(const std::string& s) {
  std::string out = "\"";
  for (char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if ((unsigned char)c < 0x20) {
          char hex[8];
          snprintf(hex, sizeof hex, "\\u%04x", c);
          out += hex;
        } else {
          out += c;
        }
    }
  }
  return out + "\"";
}

bool jsonField(const std::string& json, const char* key, std::string& out) {
  const std::string quoted = std::string("\"") + key + "\"";
  size_t pos = 0;
  int depth = 0;
  // Clé au premier niveau seulement (pas dans un objet imbriqué).
  for (size_t i = 0; i < json.size(); i++) {
    char c = json[i];
    if (c == '"') {
      size_t end = i + 1;
      while (end < json.size() && json[end] != '"') end += json[end] == '\\' ? 2 : 1;
      if (depth == 1 && json.compare(i, quoted.size(), quoted) == 0) {
        pos = end + 1;
        break;
      }
      i = end;
    } else if (c == '{' || c == '[') {
      depth++;
    } else if (c == '}' || c == ']') {
      depth--;
    }
  }
  if (!pos) return false;
  while (pos < json.size() && (json[pos] == ' ' || json[pos] == ':')) pos++;
  if (pos >= json.size()) return false;
  if (json[pos] != '"') {
    size_t end = json.find_first_of(",}]", pos);
    out = json.substr(pos, end == std::string::npos ? std::string::npos : end - pos);
    while (!out.empty() && out.back() == ' ') out.pop_back();
    return true;
  }
  out.clear();
  for (size_t i = pos + 1; i < json.size(); i++) {
    char c = json[i];
    if (c == '"') return true;
    if (c != '\\' || i + 1 >= json.size()) {
      out += c;
      continue;
    }
    char e = json[++i];
    if (e == 'n') out += '\n';
    else if (e == 't') out += '\t';
    else if (e == 'r') out += '\r';
    else if (e == 'u' && i + 4 < json.size()) {
      uint32_t cp = (uint32_t)strtoul(json.substr(i + 1, 4).c_str(), nullptr, 16);
      i += 4;
      // Paire de substitution 😀.
      if (cp >= 0xD800 && cp < 0xDC00 && i + 6 < json.size() && json[i + 1] == '\\' && json[i + 2] == 'u') {
        uint32_t low = (uint32_t)strtoul(json.substr(i + 3, 4).c_str(), nullptr, 16);
        cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        i += 6;
      }
      std::string utf8;
      if (cp < 0x80) utf8 += (char)cp;
      else if (cp < 0x800) utf8 += {(char)(0xC0 | (cp >> 6)), (char)(0x80 | (cp & 0x3F))};
      else if (cp < 0x10000) utf8 += {(char)(0xE0 | (cp >> 12)), (char)(0x80 | ((cp >> 6) & 0x3F)), (char)(0x80 | (cp & 0x3F))};
      else utf8 += {(char)(0xF0 | (cp >> 18)), (char)(0x80 | ((cp >> 12) & 0x3F)), (char)(0x80 | ((cp >> 6) & 0x3F)), (char)(0x80 | (cp & 0x3F))};
      out += utf8;
    } else {
      out += e;
    }
  }
  return false;
}

// ---------------------------------------------------------------------------
// Démarrage : annonce, connexions, demandes
// ---------------------------------------------------------------------------

bool Netplay::start(const Config& config) {
  WSADATA wsa;
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
  config_ = config;
  stopping_ = false;
  if (config.role == Role::Host) {
    listener_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    if (listener_ == INVALID_SOCKET || bind(listener_, reinterpret_cast<sockaddr*>(&address), sizeof address) != 0 || listen(listener_, 4) != 0) {
      logf("Netplay : port d'écoute impossible (%d)", WSAGetLastError());
      if (listener_ != INVALID_SOCKET) closesocket(listener_);
      listener_ = INVALID_SOCKET;
      config_.role = Role::None;
      return false;
    }
    int length = sizeof address;
    getsockname(listener_, reinterpret_cast<sockaddr*>(&address), &length);
    config_.port = ntohs(address.sin_port);
    logf("Netplay : partie proposée sur le port %d", config_.port);
    announcing_ = true;
    beaconThread_ = std::thread(&Netplay::beaconLoop, this);
    acceptThread_ = std::thread(&Netplay::acceptLoop, this);
  } else if (config.role == Role::Guest) {
    message("netplay_connecting", config.peerName);
    joinThread_ = std::thread(&Netplay::joinLoop, this);
    // Invité : annoncé « en partie » une fois relié (l'application, en arrière-plan, l'annonce sans).
    if (!beaconThread_.joinable()) beaconThread_ = std::thread(&Netplay::beaconLoop, this);
  }
  return true;
}

void Netplay::stop() {
  if (config_.role == Role::None && !beaconThread_.joinable() && !acceptThread_.joinable() && !joinThread_.joinable()) return;
  stopping_ = true;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    decision_ = 0;
  }
  decided_.notify_all();
  if (listener_ != INVALID_SOCKET) closesocket(listener_);
  listener_ = INVALID_SOCKET;
  end(true);
  stopPackets();  // jeu quitté : liaison terminée pour le cœur, avant son arrêt
  if (beaconThread_.joinable()) beaconThread_.join();
  if (acceptThread_.joinable()) acceptThread_.join();
  if (joinThread_.joinable()) joinThread_.join();
  std::lock_guard<std::mutex> lock(mutex_);
  if (ready_ != INVALID_SOCKET) closesocket(ready_);
  ready_ = INVALID_SOCKET;
  config_.role = Role::None;
  WSACleanup();
}

void Netplay::message(const std::string& key, const std::string& name) {
  std::lock_guard<std::mutex> lock(mutex_);
  messages_.emplace_back(key, name);
}

std::vector<std::pair<std::string, std::string>> Netplay::takeMessages() {
  std::lock_guard<std::mutex> lock(mutex_);
  return std::exchange(messages_, {});
}

std::string Netplay::partner() const { return partner_; }

std::string Netplay::linked() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return linked_;
}

void Netplay::setLinked(const std::string& name) {
  std::lock_guard<std::mutex> lock(mutex_);
  linked_ = name;
  busy_ = !name.empty();
}

void Netplay::announce(const std::string& deviceId, const std::string& deviceName) {
  if (beaconThread_.joinable() || deviceId.empty()) return;
  WSADATA wsa;
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;
  config_.deviceId = deviceId;
  config_.deviceName = deviceName;
  stopping_ = false;
  beaconThread_ = std::thread(&Netplay::beaconLoop, this);
}

bool Netplay::requestPending(std::string& name) const {
  std::lock_guard<std::mutex> lock(mutex_);
  name = requestName_;
  return requestPending_;
}

void Netplay::answer(bool accept) {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    decision_ = accept ? 1 : 0;
  }
  decided_.notify_all();
}

/** Annonce de la partie proposée toutes les 2 s (seulement en attente d'un invité). */
void Netplay::beaconLoop() {
  SOCKET s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  BOOL yes = TRUE;
  setsockopt(s, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&yes), sizeof yes);
  const NetplayGame& g = config_.game;
  while (!stopping_) {
    std::string beacon = "{\"app\":\"romcloud\",\"v\":" + std::to_string(kVersion) + ",\"id\":" + jsonEscape(config_.deviceId) +
                         ",\"name\":" + jsonEscape(config_.deviceName) + ",\"platform\":\"windows\"" +
                         (busy_ ? ",\"busy\":true" : "");
    if (announcing_) {
      beacon += ",\"port\":" + std::to_string(config_.port) + ",\"hosting\":{\"gameId\":" + std::to_string(g.gameId) +
                ",\"systemId\":" + jsonEscape(g.systemId) + ",\"title\":" + jsonEscape(g.title) +
                ",\"fileName\":" + jsonEscape(g.fileName) + ",\"size\":" + std::to_string(g.size) + ",\"core\":" + jsonEscape(g.core) +
                (config_.link.empty() ? std::string() : ",\"link\":" + jsonEscape(config_.link)) + "}";
    }
    beacon += "}";
    for (uint32_t address : broadcastAddresses()) {
      sockaddr_in to{};
      to.sin_family = AF_INET;
      to.sin_port = htons(kDiscoveryPort);
      to.sin_addr.s_addr = address;
      sendto(s, beacon.data(), (int)beacon.size(), 0, reinterpret_cast<sockaddr*>(&to), sizeof to);
    }
    for (int i = 0; i < 20 && !stopping_; i++) std::this_thread::sleep_for(std::chrono::milliseconds(100));
  }
  closesocket(s);
}

void Netplay::acceptLoop() {
  while (!stopping_) {
    SOCKET client = accept(listener_, nullptr, nullptr);
    if (client == INVALID_SOCKET) break;
    handleClient(client);
  }
}

/** Demande d'un invité : vérifiée, puis acceptée ou refusée par l'utilisateur (au plus 60 s). */
void Netplay::handleClient(SOCKET client) {
  setTimeout(client, 15000);
  std::string text;
  auto reply = [&](bool ok, const char* reason) {
    std::string answer = std::string("{\"ok\":") + (ok ? "true" : "false");
    if (reason) answer += std::string(",\"reason\":\"") + reason + "\"";
    answer += ",\"delay\":3,\"coreSize\":" + std::to_string(config_.coreSize) + "}";
    writeUtf(client, answer);
  };
  if (!readUtf(client, text)) {
    closesocket(client);
    return;
  }
  std::string v, name, fileName, size, core, link;
  jsonField(text, "link", link);
  jsonField(text, "v", v);
  jsonField(text, "name", name);
  jsonField(text, "fileName", fileName);
  jsonField(text, "size", size);
  jsonField(text, "core", core);
  const char* refusal = nullptr;
  if (atoi(v.c_str()) != kVersion) refusal = "version";
  else if (playing() || !announcing_) refusal = "busy";
  // Liaison : chacun son jeu, même liaison (même cœur).
  else if (!config_.link.empty() && link.empty()) refusal = "version";
  else if (link != config_.link) refusal = "game";
  else if (config_.link.empty() && (fileName != config_.game.fileName || atoll(size.c_str()) != config_.game.size)) refusal = "game";
  else if (core != config_.game.core) refusal = "core";
  if (refusal) {
    reply(false, refusal);
    closesocket(client);
    return;
  }
  std::unique_lock<std::mutex> lock(mutex_);
  requestPending_ = true;
  requestName_ = name;
  // Essai (ROMCLOUD_NETPLAY_ACCEPT) : invité accepté sans attendre l'utilisateur.
  decision_ = getenv("ROMCLOUD_NETPLAY_ACCEPT") ? 1 : -1;
  bool decided = decided_.wait_for(lock, std::chrono::seconds(60), [&] { return decision_ >= 0 || stopping_; });
  bool accepted = decided && decision_ == 1 && !stopping_;
  requestPending_ = false;
  lock.unlock();
  if (!accepted) {
    reply(false, "refused");
    closesocket(client);
    return;
  }
  reply(true, nullptr);
  if (!config_.link.empty() && !config_.packets) {
    // Liaison ouverte par le cœur (serveur Gambatte, ad hoc PPSSPP) : la connexion ne sert plus.
    closesocket(client);
    if (!config_.multi) announcing_ = false;
    lock.lock();
    linked_ = linked_.empty() ? name : linked_ + ", " + name;
    busy_ = true;
    messages_.emplace_back("link_started", name);
    return;
  }
  announcing_ = false;
  lock.lock();
  ready_ = client;
  readyHost_ = true;
  readyDelay_ = 3;
  readyPartner_ = name;
}

/** Invité : connexion à l'hôte, demande, réponse (l'hôte a 90 s pour accepter). */
void Netplay::joinLoop() {
  SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  sockaddr_in to{};
  to.sin_family = AF_INET;
  to.sin_port = htons((u_short)config_.port);
  inet_pton(AF_INET, config_.address.c_str(), &to.sin_addr);
  // Connexion non bloquante, 5 s au plus.
  u_long nonBlocking = 1;
  ioctlsocket(s, FIONBIO, &nonBlocking);
  connect(s, reinterpret_cast<sockaddr*>(&to), sizeof to);
  WSAPOLLFD pfd{s, POLLWRNORM, 0};
  int ready = WSAPoll(&pfd, 1, 5000);
  int error = 0;
  int length = sizeof error;
  getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &length);
  nonBlocking = 0;
  ioctlsocket(s, FIONBIO, &nonBlocking);
  if (ready <= 0 || error != 0 || (pfd.revents & (POLLERR | POLLHUP))) {
    closesocket(s);
    message("netplay_unreachable", config_.peerName);
    return;
  }
  const NetplayGame& g = config_.game;
  std::string join = "{\"v\":" + std::to_string(kVersion) + ",\"name\":" + jsonEscape(config_.deviceName) +
                     ",\"platform\":\"windows\",\"fileName\":" + jsonEscape(g.fileName) + ",\"size\":" + std::to_string(g.size) +
                     ",\"core\":" + jsonEscape(g.core) + ",\"coreSize\":" + std::to_string(config_.coreSize) +
                     (config_.link.empty() ? std::string() : ",\"link\":" + jsonEscape(config_.link)) + "}";
  setTimeout(s, 90000);
  std::string text;
  if (!writeUtf(s, join) || !readUtf(s, text)) {
    closesocket(s);
    if (!stopping_) message("netplay_unreachable", config_.peerName);
    return;
  }
  std::string ok, reason, delay, coreSize;
  jsonField(text, "ok", ok);
  jsonField(text, "reason", reason);
  jsonField(text, "delay", delay);
  jsonField(text, "coreSize", coreSize);
  if (ok != "true") {
    closesocket(s);
    const std::string key = reason == "refused" ? "netplay_refused" : reason == "busy" ? "netplay_busy"
                          : reason == "version" ? "netplay_other_version" : "netplay_other_game";
    message(key, config_.peerName);
    return;
  }
  setTimeout(s, 0);
  if (atoll(coreSize.c_str()) > 0 && atoll(coreSize.c_str()) != config_.coreSize) message("netplay_core_differs", config_.peerName);
  std::lock_guard<std::mutex> lock(mutex_);
  ready_ = s;
  readyHost_ = false;
  readyDelay_ = std::max(1, atoi(delay.c_str()));
  readyPartner_ = config_.peerName;
}

void Netplay::leave() {
  end(true);
  if (config_.role == Role::Host && listener_ != INVALID_SOCKET) announcing_ = true;
}

// ---------------------------------------------------------------------------
// Partie : touches image par image (boucle principale)
// ---------------------------------------------------------------------------

void Netplay::begin(SOCKET socket, bool host, int delay) {
  u_long nonBlocking = 1;
  ioctlsocket(socket, FIONBIO, &nonBlocking);
  BOOL noDelay = TRUE;
  setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof noDelay);
  socket_ = socket;
  host_ = host;
  localPort_ = host ? 0 : 1;
  remotePort_ = 1 - localPort_;
  delay_ = (unsigned)std::clamp(delay, 1, 30);
  buffer_.clear();
  hasPending_ = false;
  resync_ = false;
  waiting_ = false;
  state_ = State::Starting;
  busy_ = true;
  desyncs_ = 0;
  logf("Netplay : %s avec %s (%s, délai %u images)", config_.packets ? "liaison" : "partie", partner_.c_str(), host ? "hôte" : "invité", delay_);
  message(config_.link.empty() ? "netplay_started" : "link_started", partner_);
}

void Netplay::end(bool notify) {
  if (socket_ != INVALID_SOCKET) {
    uint8_t bye[5] = {kBye, 0, 0, 0, 0};
    send(socket_, reinterpret_cast<const char*>(bye), 5, 0);
    closesocket(socket_);
    socket_ = INVALID_SOCKET;
    if (config_.packets) logf("Netplay : fin de la liaison (%lu paquet(s) envoyé(s), %lu reçu(s))", packetsOut_, packetsIn_);
    else logf("Netplay : fin de la partie à l'image %u (%d écart(s) d'état recalé(s))", frame_, desyncs_);
    if (notify && state_ != State::Off) message(config_.link.empty() ? "netplay_ended" : "link_ended", partner_);
  }
  state_ = State::Off;
  inFrame_ = false;
  waiting_ = false;
  busy_ = !linked().empty();
}

void Netplay::resetRings() {
  for (auto& p : local_) p = Pad{};
  for (auto& p : remote_) p = Pad{};
  std::fill(std::begin(remoteFrame_), std::end(remoteFrame_), 0u);
  std::fill(std::begin(mineFrame_), std::end(mineFrame_), 0u);
  std::fill(std::begin(hostFrame_), std::end(hostFrame_), 0u);
  // Premières images (moins que le délai) : sans touche des deux côtés.
  for (uint32_t f = 0; f < delay_; f++) remoteFrame_[f] = f + 1;
  sent_ = delay_;
  frame_ = 0;
}

bool Netplay::sendMessage(uint8_t type, const void* payload, size_t size, const void* extra, size_t extraSize) {
  uint8_t header[5];
  header[0] = type;
  put32(header + 1, (uint32_t)(size + extraSize));
  return sendAll(socket_, header, 5) && (!size || sendAll(socket_, payload, size)) && (!extraSize || sendAll(socket_, extra, extraSize));
}

size_t Netplay::serialize() {
  size_t size = g.api.serialize_size();
  if (!size) return 0;
  if (state_buffer_.size() < size) state_buffer_.resize(size);
  return g.api.serialize(state_buffer_.data(), size) ? size : 0;
}

bool Netplay::sendState(uint32_t frame) {
  size_t size = serialize();
  if (!size) return false;
  // L'hôte repart aussi de l'état envoyé : certains cœurs (FBNeo) ne restaurent pas tout depuis un
  // état ; l'invité, lui, en repart forcément. Les deux reprennent ainsi exactement du même point.
  g.api.unserialize(state_buffer_.data(), size);
  uint8_t head[4];
  put32(head, frame);
  return sendMessage(kState, head, 4, state_buffer_.data(), size);
}

/**
 * Empreinte de la partie : mémoire du jeu si le cœur la fournit, sinon son état complet. L'état
 * complet de certains cœurs varie sans changer la partie (FBNeo : état interne du son, différent
 * même en rejouant les mêmes images) : comparé, il ferait recaler l'invité sans raison.
 */
uint32_t Netplay::checkHash() {
  const size_t ramSize = g.api.get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
  const auto* ram = static_cast<const uint8_t*>(g.api.get_memory_data(RETRO_MEMORY_SYSTEM_RAM));
  if (ram && ramSize) return fnv(ram, ramSize) | 1;
  size_t size = serialize();
  return size ? fnv(state_buffer_.data(), size) | 1 : 0;
}

void Netplay::compare(uint32_t frame) {
  unsigned slot = (frame / kCheck) % kChecks;
  if (mineFrame_[slot] != frame + 1 || hostFrame_[slot] != frame + 1) return;
  if (mineHash_[slot] == hostHash_[slot] || hasPending_) return;
  desyncs_++;
  logf("Netplay : état différent à l'image %u, état de l'hôte demandé", frame);
  if (!sendMessage(kResync, nullptr, 0)) end(true);
}

void Netplay::onMessage(uint8_t type, const uint8_t* p, uint32_t size) {
  if (type == kInput && size >= 14) {
    uint32_t frame = get32(p);
    Pad& pad = remote_[frame % kRing];
    pad.buttons = (uint16_t)((p[4] << 8) | p[5]);
    for (int i = 0; i < 4; i++) pad.analog[i] = (int16_t)((p[6 + 2 * i] << 8) | p[7 + 2 * i]);
    remoteFrame_[frame % kRing] = frame + 1;
  } else if (type == kState && size >= 4 && !host_) {
    pending_.assign(p + 4, p + size);
    pendingFrame_ = get32(p);
    hasPending_ = true;
  } else if (type == kCheck && size >= 8 && !host_) {
    uint32_t frame = get32(p);
    unsigned slot = (frame / kCheck) % kChecks;
    hostFrame_[slot] = frame + 1;
    hostHash_[slot] = get32(p + 4);
    compare(frame);
  } else if (type == kResync && host_) {
    resync_ = true;
  } else if (type == kPacket && packetsActive_ && g.netpacket.receive) {
    packetsIn_++;
    g.netpacket.receive(p, size, host_ ? 1 : 0);
  } else if (type == kBye) {
    end(true);
  }
}

// ---------------------------------------------------------------------------
// Liaison entre consoles : paquets du cœur (interface netpacket)
// ---------------------------------------------------------------------------

void Netplay::packetSend(int, const void* buf, size_t len, uint16_t) {
  Netplay* self = g.netplay;
  if (!self || self->socket_ == INVALID_SOCKET || !buf || !len) return;
  self->packetsOut_++;
  if (!self->sendMessage(kPacket, buf, len)) self->end(true);
}

void Netplay::packetPollReceive() {
  Netplay* self = g.netplay;
  if (self && self->socket_ != INVALID_SOCKET && !self->pump()) self->end(true);
}

/** Session terminée : signalée au cœur hors de ses propres appels (début d'image). */
void Netplay::stopPackets() {
  if (!packetsActive_) return;
  packetsActive_ = false;
  if (host_ && g.netpacket.disconnected) g.netpacket.disconnected(1);
  if (g.netpacket.stop) g.netpacket.stop();
}

Netplay::Frame Netplay::preparePackets() {
  if (state_ == State::Starting) {
    if (!g.hasNetpacket || !g.netpacket.start || !g.netpacket.receive) {
      logf("Netplay : le cœur n'a pas d'interface de liaison (netpacket)");
      end(true);
      return Frame::Solo;
    }
    g.netpacket.start(host_ ? 0 : 1, &Netplay::packetSend, &Netplay::packetPollReceive);
    packetsActive_ = true;
    if (host_ && g.netpacket.connected && !g.netpacket.connected(1)) {
      end(true);
      stopPackets();
      return Frame::Solo;
    }
    state_ = State::Running;
  }
  if (!pump()) end(true);
  if (state_ != State::Running) {
    stopPackets();
    return Frame::Solo;
  }
  if (g.netpacket.poll) g.netpacket.poll();
  return Frame::Solo;
}

/** Lit ce qui est arrivé sans attendre et traite les messages complets ; faux si la partie est finie. */
bool Netplay::pump() {
  char chunk[65536];
  for (;;) {
    int n = recv(socket_, chunk, sizeof chunk, 0);
    if (n == SOCKET_ERROR) {
      if (WSAGetLastError() == WSAEWOULDBLOCK) break;
      return false;
    }
    if (n == 0) return false;
    buffer_.insert(buffer_.end(), chunk, chunk + n);
  }
  size_t used = 0;
  while (buffer_.size() - used >= 5) {
    uint32_t size = get32(buffer_.data() + used + 1);
    if (buffer_.size() - used - 5 < size) break;
    onMessage(buffer_[used], buffer_.data() + used + 5, size);
    if (state_ == State::Off) return false;
    used += 5 + size;
  }
  if (used) buffer_.erase(buffer_.begin(), buffer_.begin() + (ptrdiff_t)used);
  return true;
}

Netplay::Frame Netplay::prepare(const LocalInput& local) {
  if (state_ == State::Off) stopPackets();
  if (state_ == State::Off) {
    // Connexion acceptée par le fil de connexion : la partie commence à cette image.
    SOCKET s;
    bool host;
    int delay;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (ready_ == INVALID_SOCKET) return Frame::Solo;
      s = std::exchange(ready_, INVALID_SOCKET);
      host = readyHost_;
      delay = readyDelay_;
      partner_ = readyPartner_;
    }
    begin(s, host, delay);
  }
  if (config_.packets) return preparePackets();
  auto fail = [&] {
    end(true);
    return Frame::Solo;
  };
  auto wait = [&] {
    if (!waiting_) {
      waiting_ = true;
      waitSince_ = nowMs();
    } else if (nowMs() - waitSince_ > kTimeoutMs) {
      return fail();
    }
    return Frame::Wait;
  };
  if (state_ == State::Starting) {
    // Départ : tampons remis à zéro, puis état de l'hôte copié chez l'invité, image 0 des deux côtés.
    if (host_) {
      resetRings();
      if (!sendState(0)) {
        logf("Netplay : état du cœur impossible à copier (taille %zu)", (size_t)g.api.serialize_size());
        return fail();
      }
    } else {
      if (!hasPending_) {
        if (!waiting_) resetRings();  // une seule fois, avant l'arrivée de l'état et des premières touches
        if (!pump()) return fail();
        if (!hasPending_) return wait();
      }
      g.api.unserialize(pending_.data(), pending_.size());
      hasPending_ = false;
    }
    waiting_ = false;
    state_ = State::Running;
  }
  if (!pump()) return fail();
  if (host_ && resync_) {
    resync_ = false;
    if (!sendState(frame_)) return fail();
  }
  if (!host_ && hasPending_ && pendingFrame_ <= frame_) {
    g.api.unserialize(pending_.data(), pending_.size());
    frame_ = pendingFrame_;
    hasPending_ = false;
  }
  // Touches de ce joueur pour l'image frame + délai (une seule fois par image).
  const uint32_t target = frame_ + delay_;
  if (target >= sent_) {
    Pad pad;
    for (unsigned b = 0; b < 16; b++) {
      if (local(0, RETRO_DEVICE_JOYPAD, 0, b)) pad.buttons |= (uint16_t)(1 << b);
    }
    for (unsigned i = 0; i < 4; i++) pad.analog[i] = local(0, RETRO_DEVICE_ANALOG, i / 2, i % 2);
    for (uint32_t f = sent_; f <= target; f++) {
      local_[f % kRing] = pad;
      uint8_t m[14];
      put32(m, f);
      m[4] = (uint8_t)(pad.buttons >> 8);
      m[5] = (uint8_t)pad.buttons;
      for (int i = 0; i < 4; i++) {
        m[6 + 2 * i] = (uint8_t)((uint16_t)pad.analog[i] >> 8);
        m[7 + 2 * i] = (uint8_t)pad.analog[i];
      }
      if (!sendMessage(kInput, m, sizeof m)) return fail();
    }
    sent_ = target + 1;
  }
  const unsigned slot = frame_ % kRing;
  if (remoteFrame_[slot] != frame_ + 1) return wait();
  waiting_ = false;
  current_[localPort_] = local_[slot];
  current_[remotePort_] = remote_[slot];
  inFrame_ = true;
  return Frame::Ready;
}

void Netplay::finishFrame() {
  if (!inFrame_) return;
  inFrame_ = false;
  frame_++;
  if (frame_ % kCheck) return;
  const uint32_t hash = checkHash();
  if (!hash) return;
  if (host_) {
    uint8_t m[8];
    put32(m, frame_);
    put32(m + 4, hash);
    if (!sendMessage(kCheck, m, sizeof m)) end(true);
  } else {
    const unsigned slot = (frame_ / kCheck) % kChecks;
    mineFrame_[slot] = frame_ + 1;
    mineHash_[slot] = hash;
    compare(frame_);
  }
}

bool Netplay::input(unsigned port, unsigned device, unsigned index, unsigned id, int16_t* value) const {
  if (!inFrame_) return false;
  *value = 0;
  if (port > 1) return true;
  const Pad& pad = current_[port];
  switch (device & RETRO_DEVICE_MASK) {
    case RETRO_DEVICE_JOYPAD:
      if (id == RETRO_DEVICE_ID_JOYPAD_MASK) *value = (int16_t)pad.buttons;
      else if (id < 16) *value = (int16_t)((pad.buttons >> id) & 1);
      break;
    case RETRO_DEVICE_ANALOG:
      if (index < 2 && id < 2) *value = pad.analog[index * 2 + id];
      break;
    default: break;  // souris, pistolet… : non partagés, au repos des deux côtés
  }
  return true;
}
