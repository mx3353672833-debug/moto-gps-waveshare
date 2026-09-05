#include "nav_app/nav_app.hpp"

#include <utility>

namespace moto::nav {

NavApp::NavApp(NavCoreConfig config) : core_(config) {}

void NavApp::set_presenter(PresentCallback callback, void* context) noexcept {
  presenter_ = callback;
  presenter_context_ = context;
}

NavCommands NavApp::handle(NavEvent event) {
  NavCommands commands = core_.handle(std::move(event));
  present();
  return commands;
}

void NavApp::present() const {
  if (presenter_ != nullptr) {
    presenter_(core_.snapshot(), presenter_context_);
  }
}

NavSnapshot NavApp::snapshot() const { return core_.snapshot(); }

}  // namespace moto::nav
