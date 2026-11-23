#include <pebble.h>

enum { ELAPSED, DISTANCE, PACE, HEART, STATE, ZONE, BATTERY, MAX_HEART, MODE, COUNT, REST, TITLE };
static Window *window;
static Layer *display;
static uint32_t elapsed, meters, pace, heart, state, zone, battery, max_heart;
static uint32_t mode, count, rest;
static char title[32];
static time_t received;

static void label(GContext *ctx, const char *text, const char *font, GRect bounds) {
  graphics_draw_text(ctx, text, fonts_get_system_font(font), bounds,
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static GColor zone_color(unsigned index) {
#if defined(PBL_COLOR)
  const GColor colors[] = { GColorLightGray, GColorVividCerulean, GColorMintGreen,
                           GColorIcterine, GColorRajah, GColorSunsetOrange };
  return colors[index];
#else
  return GColorWhite;
#endif
}

static void draw_gauge(GContext *ctx, int left, int top, int width, unsigned active) {
  const char *labels[] = { "<1", "1", "2", "3", "4", "5" };
  for (unsigned i = 0; i < 6; ++i) {
    int x = left + i * (width + 3) / 6;
    int end = left + (i + 1) * (width + 3) / 6 - 3;
    int segment_width = end - x;
    GRect segment = GRect(x, top, segment_width, 10);
    bool selected = active == i + 1;
    GColor color = zone_color(i);
    graphics_context_set_stroke_color(ctx, color);
    graphics_draw_rect(ctx, segment);
    if (selected) {
      graphics_context_set_fill_color(ctx, color);
      graphics_fill_rect(ctx, segment, 0, GCornerNone);
      // Below Z1 covers the full range from zero to 50% of max HR.
      unsigned lower = i == 0 ? 0 : 40 + i * 10;
      unsigned upper = i == 0 ? 50 : lower + 10;
      unsigned position = 50;
      if (max_heart >= 100 && max_heart <= 240) {
        unsigned percent = heart * 10000 / max_heart;
        unsigned floor = lower * 100, ceiling = upper * 100;
        percent = percent < floor ? floor : percent > ceiling ? ceiling : percent;
        position = (percent - floor) * 100 / (ceiling - floor);
      }
      int marker = x + 2 + position * (segment_width - 5) / 100;
      graphics_context_set_fill_color(ctx, GColorBlack);
      graphics_fill_rect(ctx, GRect(marker, top + 1, 2, 8), 0, GCornerNone);
    }
    graphics_context_set_text_color(ctx, selected ? color : GColorWhite);
    label(ctx, labels[i], selected ? FONT_KEY_GOTHIC_18_BOLD : FONT_KEY_GOTHIC_14,
          GRect(x - 2, top + 10, segment_width + 4, 20));
  }
  graphics_context_set_text_color(ctx, GColorWhite);
}

static void draw_strength(GContext *ctx, int w, int h, bool live,
    const char *clock, const char *status, unsigned active_zone) {
  char text[40];
  int inset = PBL_IF_ROUND_ELSE(w / 6, w / 10);
  graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorRajah, GColorWhite));
  label(ctx, title[0] ? title : "Strength", PBL_IF_ROUND_ELSE(FONT_KEY_GOTHIC_14_BOLD, FONT_KEY_GOTHIC_18_BOLD),
      GRect(inset, h * 8 / 100, w - 2 * inset, 20));
  graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
  snprintf(text, sizeof(text), "%s  /  %lu %s", clock, (unsigned long)count, count == 1 ? "set" : "sets");
  label(ctx, text, FONT_KEY_GOTHIC_18, GRect(0, h * 18 / 100, w, 24));

  bool resting = live && rest > 0 && rest < 601;
  bool complete = live && rest == 601;
  if (resting || complete) {
    time_t age = time(NULL) - received;
    unsigned left = resting && age >= 0 && (uint32_t)age < rest ? rest - (uint32_t)age : 0;
    snprintf(text, sizeof(text), "%u:%02u", left / 60, left % 60);
    graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(left ? GColorElectricBlue : GColorMintGreen, GColorWhite));
    label(ctx, text, FONT_KEY_BITHAM_42_BOLD, GRect(0, h * 29 / 100, w, 50));
    label(ctx, left ? "Rest" : "Rest complete", FONT_KEY_GOTHIC_18_BOLD,
        GRect(0, h * 57 / 100, w, 24));
    graphics_context_set_text_color(ctx, active_zone ? zone_color(active_zone - 1) : GColorWhite);
    if (heart) snprintf(text, sizeof(text), "%lu bpm", (unsigned long)heart);
    else snprintf(text, sizeof(text), "-- bpm");
    label(ctx, text, FONT_KEY_GOTHIC_14, GRect(0, h * 69 / 100, w, 20));
  } else {
    graphics_context_set_text_color(ctx, active_zone ? zone_color(active_zone - 1) : GColorWhite);
    if (live && heart) snprintf(text, sizeof(text), "%lu", (unsigned long)heart);
    else snprintf(text, sizeof(text), "--");
    label(ctx, text, FONT_KEY_BITHAM_42_BOLD, GRect(0, h * 29 / 100, w, 50));
    if (status) snprintf(text, sizeof(text), "%s", status);
    else if (active_zone > 1) snprintf(text, sizeof(text), "bpm  /  Zone %u", active_zone - 1);
    else if (active_zone == 1) snprintf(text, sizeof(text), "bpm  /  Below Z1");
    else snprintf(text, sizeof(text), "Heart rate");
    label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, h * 57 / 100, w, 24));
  }
  draw_gauge(ctx, inset, h * 78 / 100, w - 2 * inset, active_zone);
}

static void draw(Layer *layer, GContext *ctx) {
  GRect frame = layer_get_bounds(layer);
  int w = frame.size.w, h = frame.size.h;
  bool stale = !received || time(NULL) - received > 8;
  bool live = !stale && state == 0;
  graphics_context_set_text_color(ctx, GColorWhite);
  char text[32];
  if (!received) {
    label(ctx, "Workout Live", FONT_KEY_GOTHIC_24_BOLD, GRect(0, h / 4, w, 32));
    graphics_draw_text(ctx, "Start a workout in Pebble Health.",
        fonts_get_system_font(FONT_KEY_GOTHIC_18), GRect(w / 7, h / 2, w * 5 / 7, h / 3),
        GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
    return;
  }

  const char *status = state == 2 ? "Finished" : stale ? "Signal lost" : state == 1 ? "Paused" : NULL;
  char time[16];
  if (elapsed >= 3600) snprintf(time, sizeof(time), "%lu:%02lu:%02lu",
      (unsigned long)(elapsed / 3600), (unsigned long)(elapsed / 60 % 60), (unsigned long)(elapsed % 60));
  else snprintf(time, sizeof(time), "%02lu:%02lu", (unsigned long)(elapsed / 60), (unsigned long)(elapsed % 60));
  unsigned active = live && heart && zone <= 6 ? zone : 0;
  if (mode == 1) {
    draw_strength(ctx, w, h, live, time, status, active);
    return;
  }
  snprintf(text, sizeof(text), "%s%s%s", time, status ? "  " : "", status ? status : "");
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, h * 5 / 100, w, 23));

  if (live && heart) snprintf(text, sizeof(text), "%lu", (unsigned long)heart);
  else snprintf(text, sizeof(text), "--");
  graphics_context_set_text_color(ctx, active ? zone_color(active - 1) : GColorWhite);
  label(ctx, text, FONT_KEY_BITHAM_42_BOLD, GRect(0, h * 16 / 100, w, 50));
  if (active > 1) snprintf(text, sizeof(text), "bpm  /  Zone %u", active - 1);
  else if (active == 1) snprintf(text, sizeof(text), "bpm  /  Below Z1");
  else if (live && heart) snprintf(text, sizeof(text), "bpm  /  Set max HR");
  else snprintf(text, sizeof(text), "Heart rate");
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, h * 43 / 100, w, 23));

  draw_gauge(ctx, w / 10, h * 59 / 100, w - 2 * (w / 10), active);
  int left = PBL_IF_ROUND_ELSE(w / 10, 4);
  int metric_width = w / 2 - left;
  int metric_top = h * 76 / 100;
  if (mode == 3) {
    snprintf(text, sizeof(text), "%.15s  /  %lu %s", title[0] ? title : "Workout",
        (unsigned long)count, count == 1 ? "lap" : "laps");
    label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(left, metric_top, w - 2 * left, 23));
    return;
  }
  snprintf(text, sizeof(text), "%lu.%02lu km", (unsigned long)(meters / 1000), (unsigned long)(meters % 1000 / 10));
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(left, metric_top, metric_width, 23));
  if (mode == 2) {
    if (live && pace) snprintf(text, sizeof(text), "%lu km/h", (unsigned long)((3600 + pace / 2) / pace));
    else snprintf(text, sizeof(text), "-- km/h");
  } else if (live && pace) {
    snprintf(text, sizeof(text), "%lu:%02lu/km", (unsigned long)(pace / 60), (unsigned long)(pace % 60));
  } else snprintf(text, sizeof(text), "-- /km");
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(w / 2, metric_top, metric_width, 23));
  if (battery && !stale) {
    snprintf(text, sizeof(text), "Strap %lu%%", (unsigned long)(battery - 1));
    label(ctx, text, FONT_KEY_GOTHIC_14, GRect(0, h * 88 / 100, w, 17));
  }
}

static void inbox(DictionaryIterator *iter, void *context) {
  mode = 0;
  uint32_t *fields[] = { &elapsed, &meters, &pace, &heart, &state, &zone, &battery, &max_heart,
                        &mode, &count, &rest };
  for (unsigned i = 0; i < sizeof(fields) / sizeof(fields[0]); ++i) {
    Tuple *tuple = dict_find(iter, i);
    if (tuple && tuple->type == TUPLE_UINT && tuple->length == 4) *fields[i] = tuple->value->uint32;
  }
  Tuple *name = dict_find(iter, TITLE);
  title[0] = 0;
  if (name && name->type == TUPLE_CSTRING) snprintf(title, sizeof(title), "%s", name->value->cstring);
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
