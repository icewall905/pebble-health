#pragma once

#include <stdbool.h>
#include <time.h>

// The sport values are indices in the watch's start menu and Android watchSports.
enum { DETECT_NONE = -1, DETECT_RUN = 0, DETECT_WALK = 1 };
enum { DETECTION_OFFER = 1, DETECTION_PHONE_RECORDING = 2 };
enum { DETECTION_SPORT_KEY = 201, DETECTION_OFFER_TIME_KEY = 202 };
enum { DETECTION_REARM_SECONDS = 120, DETECTION_OFFER_SECONDS = 120 };

typedef struct {
  time_t last_movement;
  bool handled;
} DetectionState;

// A walking warmup and the run that follows share one prompt unless there's a quiet gap.
static inline bool detection_should_offer(DetectionState *state, time_t now,
    int sport, bool phone_recording) {
  if (sport == DETECT_NONE) {
    if (now - state->last_movement >= DETECTION_REARM_SECONDS) state->handled = false;
    return false;
  }

  state->last_movement = now;
  if (state->handled || phone_recording) {
    state->handled = true;
    return false;
  }
  state->handled = true;
  return true;
}
