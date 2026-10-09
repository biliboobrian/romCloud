// Jeu à plusieurs en réseau local (netplay synchronisé), même protocole que l'application Android
// (NetplayProtocol.kt, audio_shim.cpp) : un PC joue avec un téléphone, une TV ou un autre PC.
// Chaque appareil émule le même jeu ; seules les touches circulent, image par image.
//  - Découverte : annonce UDP (JSON) toutes les 2 s sur le port 47321 ; l'hôte y ajoute le jeu et
//    son port TCP.
//  - L'invité se connecte, envoie sa demande (JSON, writeUTF de Java : longueur sur 2 octets puis
//    UTF-8 modifié) ; l'hôte accepte ou refuse (réponse JSON).
//  - Partie : état de l'hôte copié chez l'invité, puis messages type (1 octet), longueur (4 octets,
//    gros-boutiste), contenu : touches de chaque image (pour l'image f + délai), empreinte de l'état
//    toutes les 120 images, demande d'état (écart constaté), fin.
//  - Liaison entre consoles (câble, adaptateur sans fil, ad hoc) : chaque appareil émule sa console,
//    avec son jeu. Paquets du cœur (interface netpacket, gpSP) transmis tels quels ; ou connexion
//    ouverte par le cœur lui-même (Gambatte, PPSSPP : options imposées au lancement), la demande
//    acceptée suffit.
#pragma once

#include <winsock2.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

/** Jeu de la partie : le même fichier et le même cœur des deux côtés. */
struct NetplayGame {
  long long gameId = 0;
  std::string systemId, title, fileName, core;
  long long size = 0;
};

class Netplay {
 public:
  enum class Role { None, Host, Guest };
  struct Config {
    Role role = Role::None;
    NetplayGame game;
    std::string deviceId, deviceName;
    std::string address;  // invité : adresse et port de l'hôte
    int port = 0;
    std::string peerName;  // invité : nom de l'hôte
    long long coreSize = 0;
    // Liaison entre consoles : type annoncé (« gb », « gba », « psp »), paquets du cœur échangés
    // ici ([packets]), plus de deux consoles ([multi] : toujours proposée).
    std::string link;
    bool packets = false, multi = false;
  };
  /** Touches du joueur de cet appareil (port 0 de la manette locale). */
  using LocalInput = std::function<int16_t(unsigned port, unsigned device, unsigned index, unsigned id)>;
  /** Image suivante : sans réseau, prête (touches des deux joueurs connues) ou en attente. */
  enum class Frame { Solo, Ready, Wait };

  Netplay() = default;
  ~Netplay() { stop(); }
  Netplay(const Netplay&) = delete;
  Netplay& operator=(const Netplay&) = delete;

  /** Hôte : partie proposée sur le réseau local ; invité : connexion à l'hôte. Faux si impossible. */
  bool start(const Config& config);
  void stop();
  bool enabled() const { return config_.role != Role::None; }

  // Boucle principale.
  Frame prepare(const LocalInput& local);
  void finishFrame();
  /** Touches d'un joueur pendant une image de la partie (faux : touches locales habituelles). */
  bool input(unsigned port, unsigned device, unsigned index, unsigned id, int16_t* value) const;

  // Interface.
  /** Partie en cours avec l'autre joueur (son nom), ou proposée en attente d'un invité. */
  bool playing() const { return state_ == State::Running || state_ == State::Starting; }
  bool open() const { return config_.role == Role::Host && !playing() && listener_ != INVALID_SOCKET; }
  bool waiting() const { return waiting_; }
  std::string partner() const;
  /** Demande d'un invité à accepter ([name] : son nom). */
  bool requestPending(std::string& name) const;
  void answer(bool accept);
  /** Messages à afficher (« texte|nom »), dans l'ordre. */
  std::vector<std::pair<std::string, std::string>> takeMessages();
  /** Fin de la partie depuis le menu : chacun continue seul (hôte : de nouveau proposée). */
  void leave();
  bool isLink() const { return !config_.link.empty(); }
  /** Consoles reliées par le cœur lui-même (noms des autres appareils), vide sinon. */
  std::string linked() const;
  /** Invité d'une liaison ouverte par le cœur : demande acceptée avant le lancement. */
  void setLinked(const std::string& name);
  /** Annonce de cet appareil sans partie à gérer (invité d'une liaison ouverte par le cœur) : « en partie ». */
  void announce(const std::string& deviceId, const std::string& deviceName);
  /** Liaison par paquets du cœur, séparée du jeu synchronisé ; à couper avec leave(). */
  bool packets() const { return config_.packets; }
  /** Jeu synchronisé en cours (états échangés entre appareils). */
  bool synchronized() const { return playing() && config_.link.empty(); }

 private:
  enum class State { Off, Starting, Running };
  struct Pad {
    uint16_t buttons = 0;
    int16_t analog[4] = {0, 0, 0, 0};
  };

  void message(const std::string& key, const std::string& name = std::string());
  void beaconLoop();
  void acceptLoop();
  void handleClient(SOCKET client);
  void joinLoop();
  void begin(SOCKET socket, bool host, int delay);
  void end(bool notify);
  bool pump();
  bool sendMessage(uint8_t type, const void* payload, size_t size, const void* extra = nullptr, size_t extraSize = 0);
  bool sendState(uint32_t frame);
  size_t serialize();
  void compare(uint32_t frame);
  uint32_t checkHash();
  void onMessage(uint8_t type, const uint8_t* p, uint32_t size);
  void resetRings();
  Frame preparePackets();
  void stopPackets();
  static void packetSend(int flags, const void* buf, size_t len, uint16_t client);
  static void packetPollReceive();

  Config config_;
  std::atomic<bool> stopping_{false};
  SOCKET listener_ = INVALID_SOCKET;
  std::thread beaconThread_, acceptThread_, joinThread_;
  mutable std::mutex mutex_;
  std::condition_variable decided_;
  // Demande en attente de réponse (hôte) ; connexion prête à commencer (remise à la boucle principale).
  bool requestPending_ = false;
  std::string requestName_;
  int decision_ = -1;  // -1 en attente, 0 refus, 1 accord
  SOCKET ready_ = INVALID_SOCKET;
  bool readyHost_ = false;
  int readyDelay_ = 3;
  std::string readyPartner_;
  std::vector<std::pair<std::string, std::string>> messages_;
  std::atomic<bool> announcing_{false};
  std::atomic<bool> busy_{false};  // en partie avec un autre appareil (annoncé)
  std::string linked_;
  bool packetsActive_ = false;
  unsigned long packetsOut_ = 0, packetsIn_ = 0;  // paquets du cœur échangés (journal)

  // Partie (boucle principale seulement).
  State state_ = State::Off;
  SOCKET socket_ = INVALID_SOCKET;
  bool host_ = false;
  unsigned localPort_ = 0, remotePort_ = 1, delay_ = 3;
  std::string partner_;
  uint32_t frame_ = 0, sent_ = 0;
  static constexpr unsigned kRing = 256, kCheck = 120, kChecks = 8;
  Pad local_[kRing], remote_[kRing];
  uint32_t remoteFrame_[kRing] = {};
  Pad current_[2];
  bool inFrame_ = false;
  bool waiting_ = false;
  uint64_t waitSince_ = 0;
  std::vector<uint8_t> buffer_;
  std::vector<uint8_t> pending_;
  uint32_t pendingFrame_ = 0;
  bool hasPending_ = false;
  bool resync_ = false;
  int desyncs_ = 0;
  uint32_t mineFrame_[kChecks] = {}, mineHash_[kChecks] = {}, hostFrame_[kChecks] = {}, hostHash_[kChecks] = {};
  std::vector<uint8_t> state_buffer_;
};

/** Champ texte ou nombre d'un objet JSON à un niveau ([out] sans guillemets ; faux si absent). */
bool jsonField(const std::string& json, const char* key, std::string& out);
std::string jsonEscape(const std::string& s);
