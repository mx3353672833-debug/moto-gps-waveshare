#include "nav_app/nav_app.hpp"

#include <cstdlib>
#include <iostream>

namespace {

using namespace moto::nav;

int failures = 0;

#define CHECK(condition)                                                   \
  do {                                                                     \
    if (!(condition)) {                                                    \
      std::cerr << __FILE__ << ':' << __LINE__                             \
                << " CHECK failed: " #condition << '\n';                  \
      ++failures;                                                          \
    }                                                                      \
  } while (false)

struct PresenterProbe {
  int calls = 0;
  NavSnapshot last;
};

void capture_snapshot(const NavSnapshot& snapshot, void* context) {
  auto* const probe = static_cast<PresenterProbe*>(context);
  ++probe->calls;
  probe->last = snapshot;
}

void test_every_event_is_presented_from_the_core_snapshot() {
  NavApp app;
  PresenterProbe probe;
  app.set_presenter(capture_snapshot, &probe);

  app.present();
  CHECK(probe.calls == 1);
  CHECK(probe.last.state == NavState::Idle);

  app.handle(NetworkChanged{NetworkState::Online});
  CHECK(probe.calls == 2);
  CHECK(probe.last.network == NetworkState::Online);

  app.handle(BeginNavigation{Wgs84Point{39.92015, 116.410886}});
  CHECK(probe.calls == 3);
  CHECK(probe.last.state == NavState::Acquiring);
  CHECK(app.snapshot().state == probe.last.state);
}

void test_display_page_is_shared_state_and_survives_navigation_reset() {
  NavApp app;
  app.handle(DisplayPageSelected{DisplayPage::Compass});
  CHECK(app.snapshot().display_page == DisplayPage::Compass);
  app.handle(BeginNavigation{Wgs84Point{39.92015, 116.410886}});
  CHECK(app.snapshot().display_page == DisplayPage::Compass);
  app.handle(CancelNavigation{});
  CHECK(app.snapshot().display_page == DisplayPage::Compass);
  app.handle(DisplayPageSelected{DisplayPage::Speed});
  CHECK(app.snapshot().display_page == DisplayPage::Speed);
}

}  // namespace

int main() {
  test_every_event_is_presented_from_the_core_snapshot();
  test_display_page_is_shared_state_and_survives_navigation_reset();
  if (failures != 0) {
    std::cerr << failures << " nav_app checks failed\n";
    return EXIT_FAILURE;
  }
  std::cout << "All nav_app checks passed\n";
  return EXIT_SUCCESS;
}
