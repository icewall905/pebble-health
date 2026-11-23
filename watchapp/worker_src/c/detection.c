#include <pebble_worker.h>
#include "../../shared/detection.h"

#if defined(PBL_HEALTH)
static DetectionState detection;
static time_t last_phone_recording;

static void check_activity(void) {
  const time_t now = time(NULL);
  const HealthActivityMask activity = health_service_peek_current_activities();
  const int sport = activity & (HealthActivitySleep | HealthActivityOpenWorkout) ? DETECT_NONE :
      activity & HealthActivityRun ? DETECT_RUN : activity & HealthActivityWalk ? DETECT_WALK : DETECT_NONE;
  const bool phone_recording = last_phone_recording && now - last_phone_recording < 300;
  if (!detection_should_offer(&detection, now, sport, phone_recording)) return;

  const time_t previous_offer = persist_read_int(DETECTION_OFFER_TIME_KEY);
  if (previous_offer && now >= previous_offer && now - previous_offer < DETECTION_REARM_SECONDS) return;
  if (persist_write_int(DETECTION_SPORT_KEY, sport) < 0 ||
      persist_write_int(DETECTION_OFFER_TIME_KEY, now) < 0) return;

  AppWorkerMessage message = { .data0 = sport };
  app_worker_send_message(DETECTION_OFFER, &message);
  worker_launch_app();
}

static void movement(HealthEventType event, void *context) {
  if (event == HealthEventMovementUpdate || event == HealthEventSignificantUpdate ||
      event == HealthEventSleepUpdate) check_activity();
}

static void minute_tick(struct tm *time, TimeUnits units) {
  check_activity();
}

static void from_app(uint16_t type, AppWorkerMessage *message) {
  if (type == DETECTION_PHONE_RECORDING) last_phone_recording = message->data0 ? time(NULL) : 0;
}
#endif

int main(void) {
#if defined(PBL_HEALTH)
  app_worker_message_subscribe(from_app);
  health_service_events_subscribe(movement, NULL);
  tick_timer_service_subscribe(MINUTE_UNIT, minute_tick);
#endif
  worker_event_loop();
#if defined(PBL_HEALTH)
  tick_timer_service_unsubscribe();
  health_service_events_unsubscribe();
  app_worker_message_unsubscribe();
#endif
}
