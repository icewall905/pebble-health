#include <pebble.h>

enum { ELAPSED, DISTANCE, PACE, HEART, STATE };
static Window *window;
static Layer *display;
static uint32_t elapsed, meters, pace, heart, state;
static time_t received;

static void draw(Layer *layer, GContext *ctx) {
  GRect frame = layer_get_bounds(layer);
  int w = frame.size.w, h = frame.size.h;
  bool stale = !received || time(NULL) - received > 8;
  graphics_context_set_text_color(ctx, GColorWhite);
  const char *header = !received ? "Open Pebble Health" : stale && state != 2 ? "Signal lost" :
                       state == 2 ? "Finished" : state == 1 ? "Paused" : "Running stats";
  graphics_draw_text(ctx, header, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
      GRect(0, h * 9 / 100, w, 24), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  if (!received) {
    graphics_draw_text(ctx, "Start a workout with live watch stats enabled.",
        fonts_get_system_font(FONT_KEY_GOTHIC_18), GRect(w / 10, h / 3, w * 8 / 10, h / 2),
        GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    return;
  }
  char text[32];
  if (elapsed >= 3600) snprintf(text, sizeof(text), "%lu:%02lu:%02lu",
      (unsigned long)(elapsed / 3600), (unsigned long)(elapsed / 60 % 60), (unsigned long)(elapsed % 60));
  else snprintf(text, sizeof(text), "%02lu:%02lu", (unsigned long)(elapsed / 60), (unsigned long)(elapsed % 60));
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_BITHAM_34_MEDIUM_NUMBERS),
      GRect(0, h * 23 / 100, w, 40), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  snprintf(text, sizeof(text), "%lu.%02lu km", (unsigned long)(meters / 1000), (unsigned long)(meters % 1000 / 10));
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD),
      GRect(0, h * 48 / 100, w, 30), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  if (pace && !stale && state == 0) snprintf(text, sizeof(text), "%lu:%02lu /km",
      (unsigned long)(pace / 60), (unsigned long)(pace % 60));
  else snprintf(text, sizeof(text), "-- /km");
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_24),
      GRect(0, h * 64 / 100, w, 28), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  if (heart && !stale && state == 0) snprintf(text, sizeof(text), "%lu bpm", (unsigned long)heart);
  else snprintf(text, sizeof(text), "-- bpm");
  graphics_draw_text(ctx, text, fonts_get_system_font(FONT_KEY_GOTHIC_24),
      GRect(0, h * 80 / 100, w, 28), GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static void inbox(DictionaryIterator *iter, void *context) {
  uint32_t *fields[] = { &elapsed, &meters, &pace, &heart, &state };
  for (unsigned i = 0; i < sizeof(fields) / sizeof(fields[0]); ++i) {
    Tuple *tuple = dict_find(iter, i);
    if (tuple && tuple->type == TUPLE_UINT && tuple->length == 4) *fields[i] = tuple->value->uint32;
  }
  received = time(NULL);
  layer_mark_dirty(display);
}

static void tick(struct tm *time, TimeUnits units) { layer_mark_dirty(display); }

int main(void) {
  window = window_create();
  window_set_background_color(window, GColorBlack);
  Layer *root = window_get_root_layer(window);
  display = layer_create(layer_get_bounds(root));
  layer_set_update_proc(display, draw);
  layer_add_child(root, display);
  window_stack_push(window, false);
  app_message_register_inbox_received(inbox);
  app_message_open(128, 32);
  tick_timer_service_subscribe(SECOND_UNIT, tick);
  app_event_loop();
  tick_timer_service_unsubscribe();
  app_message_deregister_callbacks();
  layer_destroy(display);
  window_destroy(window);
}
