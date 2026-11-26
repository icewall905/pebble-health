#include <assert.h>
#include "../shared/detection.h"

int main(void) {
  assert(!detection_offer_is_fresh(1000, 0, 0));
  assert(!detection_offer_is_fresh(1000, 1001, 0));
  assert(detection_offer_is_fresh(1000, 1000, 0));
  assert(detection_offer_is_fresh(1119, 1000, 0));
  assert(!detection_offer_is_fresh(1120, 1000, 0));
  assert(!detection_offer_is_fresh(1001, 1000, 1000));

  DetectionState state = {0};
  assert(!detection_should_offer(&state, 1000, DETECT_NONE, false));
  assert(detection_should_offer(&state, 1060, DETECT_WALK, false));
  assert(!detection_should_offer(&state, 1120, DETECT_RUN, false));
  assert(!detection_should_offer(&state, 1180, DETECT_NONE, false));
  assert(!detection_should_offer(&state, 1240, DETECT_RUN, false));
  assert(!detection_should_offer(&state, 1360, DETECT_NONE, false));
  assert(!detection_should_offer(&state, 1420, DETECT_RUN, true));
  assert(!detection_should_offer(&state, 1480, DETECT_RUN, false));
  assert(!detection_should_offer(&state, 1600, DETECT_NONE, false));
  assert(detection_should_offer(&state, 1660, DETECT_RUN, false));
}
