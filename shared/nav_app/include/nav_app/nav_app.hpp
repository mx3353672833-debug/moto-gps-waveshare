#pragma once

#include "nav_core/nav_core.hpp"

namespace moto::nav {

// The shared application boundary guarantees that every platform follows the
// same event -> core -> snapshot -> presenter order. Platform adapters execute
// returned commands, but never mutate display state directly.
class NavApp {
 public:
  using PresentCallback = void (*)(const NavSnapshot& snapshot,
                                   void* context);

  explicit NavApp(NavCoreConfig config = {});

  void set_presenter(PresentCallback callback, void* context) noexcept;
  NavCommands handle(NavEvent event);
  void present() const;

  [[nodiscard]] NavSnapshot snapshot() const;

 private:
  NavCore core_;
  PresentCallback presenter_ = nullptr;
  void* presenter_context_ = nullptr;
};

}  // namespace moto::nav
