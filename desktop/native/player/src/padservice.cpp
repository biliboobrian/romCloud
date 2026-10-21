#include "padservice.h"

#include <windows.h>

#include <SDL3/SDL.h>

#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "core.h"
#include "input.h"
#include "menu.h"
#include "padconfig.h"
#include "util.h"

// Commandes (une par ligne) :
//   config          configuration d'une manette, comme « Configurer la manette » du menu : la
//                   première manette dont on appuie sur un bouton est configurée
//   skip | reset | cancel   étape passée, manette remise par défaut, configuration annulée
//   order 2,0       joueurs : la manette du joueur 3 devient le joueur 1, celle du joueur 1 le joueur 2…
//   quit
// Sorties (JSON, une par ligne) :
//   {"type":"steps","steps":[libellés]}                       boutons de la console, dans l'ordre
//   {"type":"pads","pads":[{port,name,kind,elements}|{port,saved:true}|{port}]}
//                       manette de chaque joueur ; elements : élément de chaque étape (padStepElements)
//   {"type":"press","port":0,"button":"a"}                  bouton appuyé (choix des joueurs)
//   {"type":"wizard","device","step","message","assigned":[...]}   configuration en cours
//   {"type":"wizardDone","result":"..."}                    configuration terminée (message traduit)

namespace {

constexpr Uint32 kCommandEvent = SDL_EVENT_USER;

std::string json(const std::string& s) {
  std::string out = "\"";
  for (unsigned char c : s) {
    if (c == '"' || c == '\\') out += '\\', out += (char)c;
    else if (c < 0x20) {
      char buf[8];
      snprintf(buf, sizeof(buf), "\\u%04x", c);
      out += buf;
    } else {
      out += (char)c;
    }
  }
  return out + "\"";
}

std::string jsonList(const std::vector<std::string>& items) {
  std::string out = "[";
  for (size_t i = 0; i < items.size(); i++) out += (i ? "," : "") + json(items[i]);
  return out + "]";
}

void emit(const std::string& line) {
  std::string data = line + "\n";
  DWORD written = 0;
  WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), data.data(), (DWORD)data.size(), &written, nullptr);
}

/** Lignes de l'entrée standard, passées à la boucle d'évènements ; fin (application fermée) : « quit ». */
void readCommands() {
  HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
  std::string pending;
  char buf[512];
  DWORD read = 0;
  auto post = [](const std::string& line) {
    SDL_Event e{};
    e.type = kCommandEvent;
    e.user.data1 = new std::string(line);
    SDL_PushEvent(&e);
  };
  while (ReadFile(in, buf, sizeof(buf), &read, nullptr) && read > 0) {
    pending.append(buf, read);
    size_t end;
    while ((end = pending.find('\n')) != std::string::npos) {
      std::string line = pending.substr(0, end);
      pending.erase(0, end + 1);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      post(line);
    }
  }
  post("quit");
}

}  // namespace

int runPadService(const PadServiceArgs& args) {
  Menu menu(args.lang, "", "");
  auto tr = [&menu](const char* key) { return menu.tr(key); };
  g.options.load(args.options);
  applySavedPadMappings();  // manettes configurées pour ce système (avant leur ouverture)
  Input input;
  input.loadKeys(args.keysFile);
  input.init();
  const std::vector<PadStep> steps = PadConfig::stepsFor(args.buttons, tr);
  std::vector<std::string> labels;
  for (const auto& step : steps) labels.push_back(step.label);
  emit("{\"type\":\"steps\",\"steps\":" + jsonList(labels) + "}");

  std::string lastPads;
  auto emitPads = [&] {
    std::string out = "{\"type\":\"pads\",\"pads\":[";
    for (int port = 0; port < Input::kPorts; port++) {
      out += port ? "," : "";
      SDL_Gamepad* pad = input.padAt(port);
      if (!pad) {
        out += "{\"port\":" + std::to_string(port) + (input.savedPlayer(port).empty() ? "" : ",\"saved\":true") + "}";
        continue;
      }
      const char* name = SDL_GetGamepadName(pad);
      const char* kind = SDL_GetGamepadStringForType(SDL_GetGamepadType(pad));
      const SDL_JoystickID id = SDL_GetJoystickID(SDL_GetGamepadJoystick(pad));
      out += "{\"port\":" + std::to_string(port) + ",\"name\":" + json(name ? name : "") + ",\"kind\":" + json(kind ? kind : "") +
             ",\"elements\":" + jsonList(padStepElements(id, steps)) + "}";
    }
    out += "]}";
    if (out != lastPads) emit(out);
    lastPads = out;
  };
  emitPads();

  std::unique_ptr<PadConfig> wizard;
  std::string lastWizard;
  auto emitWizard = [&] {
    std::vector<std::string> assigned;
    for (const auto& source : wizard->assigned()) assigned.push_back(source.empty() ? "" : wizard->sourceLabel(source));
    std::string line = "{\"type\":\"wizard\",\"device\":" + json(wizard->deviceName()) + ",\"step\":" + std::to_string(wizard->stepIndex()) +
                       ",\"message\":" + json(wizard->message()) + ",\"assigned\":" + jsonList(assigned) + "}";
    if (line != lastWizard) emit(line);
    lastWizard = line;
  };
  auto endWizard = [&] {
    if (!wizard || !wizard->finished()) return;
    emit("{\"type\":\"wizardDone\",\"result\":" + json(wizard->result()) + "}");
    wizard.reset();
    lastWizard.clear();
    emitPads();
  };

  std::thread(readCommands).detach();
  bool running = true;
  while (running) {
    SDL_Event e;
    if (!SDL_WaitEventTimeout(&e, 100)) continue;
    if (e.type == kCommandEvent) {
      std::unique_ptr<std::string> command(static_cast<std::string*>(e.user.data1));
      const std::string& c = *command;
      if (c == "quit") {
        running = false;
      } else if (c == "config") {
        wizard = std::make_unique<PadConfig>(args.buttons, "", tr);
        emitWizard();
      } else if (wizard && (c == "skip" || c == "reset" || c == "cancel")) {
        if (c == "skip") wizard->skip();
        else if (c == "reset") wizard->reset();
        else wizard->cancel();
        if (!wizard->finished()) emitWizard();
        endWizard();
      } else if (c.rfind("order ", 0) == 0) {
        std::vector<SDL_JoystickID> order;
        size_t start = 6;
        while (start < c.size()) {
          size_t comma = c.find(',', start);
          if (comma == std::string::npos) comma = c.size();
          if (SDL_Gamepad* pad = input.padAt(atoi(c.substr(start, comma - start).c_str()))) {
            order.push_back(SDL_GetJoystickID(SDL_GetGamepadJoystick(pad)));
          }
          start = comma + 1;
        }
        input.setPlayerOrder(order);
        emitPads();
      }
      continue;
    }
    if (e.type == SDL_EVENT_QUIT) break;
    if (wizard && wizard->handleEvent(e)) {
      if (!wizard->finished()) emitWizard();
      endWizard();
      continue;
    }
    if (e.type == SDL_EVENT_GAMEPAD_ADDED || e.type == SDL_EVENT_GAMEPAD_REMOVED) {
      input.handleEvent(e);
      emitPads();
    } else if (e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN && !wizard) {
      const int port = input.portOf(e.gbutton.which);
      const char* button = SDL_GetGamepadStringForButton((SDL_GamepadButton)e.gbutton.button);
      if (port >= 0) emit("{\"type\":\"press\",\"port\":" + std::to_string(port) + ",\"button\":" + json(button ? button : "") + "}");
    }
  }
  input.shutdown();
  return 0;
}
