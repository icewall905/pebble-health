#include <pebble.h>
#include "../../shared/detection.h"

enum { ELAPSED, DISTANCE, PACE, HEART, STATE, ZONE, BATTERY, MAX_HEART, MODE, COUNT, REST, TITLE, TOKEN };
static Window *window;
static Layer *display;
static Window *media_window;
static Layer *media_display;
static const char *media_notice;
static time_t media_notice_until;
static unsigned media_selected;
static uint32_t elapsed, meters, pace, heart, state, zone, battery, max_heart;
static uint32_t mode, count, rest;
static char title[32];
static time_t received;
static uint32_t token, menu_token, sequence;
static bool has_token, sending, starting;
static bool wrist_requested, wrist_sampling, wrist_sampling_wanted;
static time_t wrist_received;
static unsigned wrist_bpm;
#define HEART_REQUEST_BIT UINT32_C(0x80000000)
#define SESSION_TOKEN_MASK UINT32_C(0x7fffffff)
static unsigned pending_command;
enum { HOME, SENSORS, WORKOUT, HISTORY, HISTORY_DETAIL };
static unsigned page, history_return_page;

enum { HISTORY_LIMIT = 8, HISTORY_ROW = 13, HISTORY_KEY = 203 };
static uint8_t history[HISTORY_LIMIT * HISTORY_ROW];
static unsigned history_count, history_selection;
static bool history_answered;
static time_t history_request, sensor_request, sensor_received;
static uint32_t sensor_status, sensor_bpm;
static time_t notice_until;
static const char *notice;
static Window *options_window;
static Window *utilities_window;
static SimpleMenuLayer *utilities;
static SimpleMenuLayer *options;
static SimpleMenuItem items[11];
static unsigned actions[11];
static SimpleMenuSection section;

enum { START = 1, PAUSE, RESUME, LAP, START_REST, SAVE, DISCARD, REPEAT_SET, REQUEST_SENSORS, REQUEST_HISTORY, WATCH_HEART, MEDIA_COMMAND, OPEN_FINISH, OPEN_DISCARD, TOGGLE_DETECTION, TOGGLE_AUTO_START };
enum { SPORTS, CONTROLS, END_WORKOUT, CONFIRM_DISCARD, DETECTED_WORKOUT, SETTINGS };
enum { MEDIA_PREVIOUS = 1, MEDIA_PLAY_PAUSE, MEDIA_NEXT };
static const char *sports[] = {
  "Running", "Walking", "Hiking", "Cycling", "Strength",
  "Tennis", "Rowing", "Yoga", "Beat Saber", "Other workout"
};
static unsigned menu_type;
static void show_notice(const char *message);
static void media_status(const char *message);
static void open_media(void);
#if defined(PBL_HEALTH)
static int detected_sport;
static bool detection_pending;
static time_t last_detection_offer;
static void offer_detection(void);

static bool auto_start(void) {
  return !persist_exists(DETECTION_AUTO_START_KEY) || persist_read_bool(DETECTION_AUTO_START_KEY);
}

static void toggle_detection(void) {
  if (app_worker_is_running()) {
    app_worker_kill();
    show_notice("Stopping detection");
  } else {
    AppWorkerResult result = app_worker_launch();
    if (result == APP_WORKER_RESULT_SUCCESS || result == APP_WORKER_RESULT_ALREADY_RUNNING)
      show_notice("Auto-detect enabled");
    else if (result != APP_WORKER_RESULT_ASKING_CONFIRMATION)
      show_notice("Could not enable");
  }
}
#endif

static void show_notice(const char *message) {
  notice = message;
  notice_until = time(NULL) + 7;
  layer_mark_dirty(display);
}

static void send_command(unsigned command, unsigned argument) {
  if (sending) return;
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) {
    if (command == MEDIA_COMMAND) media_status("Try again");
    else show_notice("Try again");
    return;
  }
  dict_write_uint8(out, 20, command);
  dict_write_uint8(out, 21, argument);
  dict_write_uint32(out, 23, ++sequence);
  if (command >= PAUSE && command <= REPEAT_SET) dict_write_uint32(out, 22, menu_token);
  if (command == WATCH_HEART) dict_write_uint32(out, 22, token);
  if (app_message_outbox_send() == APP_MSG_OK) {
    pending_command = command;
    sending = true;
    if (command == START) {
      starting = true;
      show_notice("Starting on phone...");
    }
  } else if (command == MEDIA_COMMAND) {
    media_status("Phone unavailable");
  } else if (command < REQUEST_SENSORS) {
    show_notice("Could not reach phone");
  }
}

static bool phone_has_live_heart(time_t now) {
  return (received && now - received <= 8 && state == 0 && heart) ||
      (sensor_received && now - sensor_received <= 12 && sensor_bpm);
}

static void set_wrist_sampling(bool wanted) {
  if (wanted == wrist_sampling_wanted) return;
  wrist_sampling_wanted = wanted;
#if defined(PBL_HEALTH)
  wrist_sampling = health_service_set_heart_rate_sample_period(wanted ? 5 : 0) && wanted;
#endif
  if (!wanted) {
    wrist_bpm = 0;
    wrist_received = 0;
  }
}

static void update_wrist_sampling(time_t now) {
  if (wrist_requested && now - received > 8) wrist_requested = false;
  set_wrist_sampling(wrist_requested || (page == SENSORS && !phone_has_live_heart(now)));
}

#if defined(PBL_HEALTH)
static void on_watch_heart(HealthEventType event, void *context) {
  if (event != HealthEventHeartRateUpdate || !wrist_sampling) return;
  const HealthValue bpm = health_service_peek_current_value(HealthMetricHeartRateRawBPM);
  const time_t now = time(NULL);
  wrist_bpm = bpm >= 25 && bpm <= 250 ? bpm : 0;
  wrist_received = now;
  layer_mark_dirty(display);
  if (wrist_bpm && wrist_requested && has_token && state == 0 && now - received <= 8 && !sending)
    send_command(WATCH_HEART, wrist_bpm);
}
#endif

static void populate_menu(unsigned type);

static void media_status(const char *message) {
  media_notice = message;
  media_notice_until = time(NULL) + 3;
  layer_mark_dirty(media_display);
}

static void show_submenu(void *type) {
  populate_menu((unsigned)(uintptr_t)type);
}

static void select_option(int index, void *context) {
  unsigned action = actions[index];
  if (action == OPEN_FINISH || action == OPEN_DISCARD) {
    const uintptr_t type = action == OPEN_FINISH ? END_WORKOUT : CONFIRM_DISCARD;
    app_timer_register(1, show_submenu, (void *)type);
    return;
  }
  window_stack_pop(true);
#if defined(PBL_HEALTH)
  if (action == TOGGLE_DETECTION) {
    window_stack_pop(true);
    toggle_detection();
    return;
  }
  if (action == TOGGLE_AUTO_START) {
    window_stack_pop(true);
    const bool on = !auto_start();
    persist_write_bool(DETECTION_AUTO_START_KEY, on);
    show_notice(on ? "Auto-start on" : "Will ask first");
    return;
  }
  if (menu_type == DETECTED_WORKOUT) {
    if (action == START) send_command(START, detected_sport);
    else if (launch_reason() == APP_LAUNCH_WORKER) window_stack_pop_all(true);
    return;
  }
#endif
  if (action) send_command(action, menu_type == SPORTS ? index : 0);
}

static void add_option(const char *title, unsigned action) {
  actions[section.num_items] = action;
  items[section.num_items++] = (SimpleMenuItem){ .title = title, .callback = select_option };
}

static void populate_menu(unsigned type) {
  if (options) simple_menu_layer_destroy(options);
  menu_type = type;
  section.num_items = 0;
  section.items = items;
  if (type == SPORTS) {
    section.title = "Start on phone";
    for (unsigned i = 0; i < sizeof(sports) / sizeof(sports[0]); i++) add_option(sports[i], START);
  } else if (type == SETTINGS) {
    section.title = "Settings";
#if defined(PBL_HEALTH)
    add_option(app_worker_is_running() ? "Auto-detect: On" : "Auto-detect: Off", TOGGLE_DETECTION);
    add_option(auto_start() ? "On detect: Start" : "On detect: Ask", TOGGLE_AUTO_START);
#else
    add_option("No auto-detection", 0);
    items[0].callback = NULL;
#endif
  } else if (type == CONTROLS) {
    section.title = "Workout controls";
    add_option(state == 1 ? "Resume" : "Pause", state == 1 ? RESUME : PAUSE);
    if (state != 1) {
      add_option(mode == 1 ? "Start set rest" : "Log lap", mode == 1 ? START_REST : LAP);
      if (mode == 1 && count > 0) add_option("Repeat last set", REPEAT_SET);
    }
    add_option("Finish workout", OPEN_FINISH);
  } else if (type == END_WORKOUT) {
    section.title = "Finish workout?";
    add_option("Save workout", SAVE);
    add_option("Discard...", OPEN_DISCARD);
    add_option("Keep recording", 0);
#if defined(PBL_HEALTH)
  } else if (type == DETECTED_WORKOUT) {
    section.title = detected_sport == DETECT_RUN ? "Run detected" : "Walk detected";
    add_option("Start on phone", START);
    add_option("Dismiss", 0);
#endif
  } else {
    section.title = "Delete recording?";
    add_option("Keep recording", 0);
    add_option("Discard workout", DISCARD);
  }
  Layer *root = window_get_root_layer(options_window);
  options = simple_menu_layer_create(layer_get_bounds(root), options_window, &section, 1, NULL);
  MenuLayer *menu = simple_menu_layer_get_menu_layer(options);
  menu_layer_set_highlight_colors(menu,
      PBL_IF_COLOR_ELSE(type == CONFIRM_DISCARD ? GColorDarkCandyAppleRed : GColorMidnightGreen, GColorBlack),
      GColorWhite);
  layer_add_child(root, simple_menu_layer_get_layer(options));
}

#if defined(PBL_HEALTH)
static void offer_detection(void) {
  if (window_stack_contains_window(utilities_window)) {
    detection_pending = true;
    return;
  }
  detection_pending = false;
  if (!app_worker_is_running() || starting ||
      (received && state < 2 && time(NULL) - received <= 8)) return;
  const time_t now = time(NULL);
  const time_t offered = persist_read_int(DETECTION_OFFER_TIME_KEY);
  if (!offered || offered == last_detection_offer || now < offered ||
      now - offered >= DETECTION_OFFER_SECONDS) return;
  const int sport = persist_read_int(DETECTION_SPORT_KEY);
  if (sport != DETECT_RUN && sport != DETECT_WALK) return;
  last_detection_offer = offered;
  detected_sport = sport;
  if (auto_start()) {
    // send_command shows "Starting on phone..." and the phone's reply drives the workout screen.
    vibes_double_pulse();
    send_command(START, sport);
    return;
  }
  populate_menu(DETECTED_WORKOUT);
  if (window_stack_get_top_window() != options_window) window_stack_push(options_window, true);
  vibes_short_pulse();
}

static void detection_message(uint16_t type, AppWorkerMessage *message) {
  if (type == DETECTION_OFFER) offer_detection();
}
#endif

static void open_options(ClickRecognizerRef recognizer, void *context) {
  if (sending && pending_command < REQUEST_SENSORS) return;
  if (page == HISTORY && history_count) {
    page = HISTORY_DETAIL;
    layer_mark_dirty(display);
    return;
  }
  if (page == HISTORY || page == HISTORY_DETAIL) return;
  if (received && state < 2 && time(NULL) - received <= 8) {
    if (!has_token) {
      show_notice("Update Pebble Health");
      return;
    }
    menu_token = token;
    populate_menu(CONTROLS);
  } else {
    populate_menu(SPORTS);
  }
  window_stack_push(options_window, true);
}

static void select_utility(int index, void *context) {
  if (index == 0) {
    window_stack_pop(true);
    history_return_page = page;
    page = HISTORY;
    notice_until = 0;
    history_selection = 0;
    history_request = 0;
    history_answered = false;
    layer_mark_dirty(display);
  } else if (index == 1) {
    populate_menu(SETTINGS);
    window_stack_push(options_window, true);
  } else {
    open_media();
  }
}

static void create_utilities(void) {
  static const SimpleMenuItem entries[] = {
    { .title = "History", .callback = select_utility },
    { .title = "Settings", .callback = select_utility },
    { .title = "Media control", .callback = select_utility },
  };
  static const SimpleMenuSection menu_section = {
    .title = "Menu", .items = entries, .num_items = ARRAY_LENGTH(entries),
  };
  utilities_window = window_create();
  Layer *root = window_get_root_layer(utilities_window);
  utilities = simple_menu_layer_create(layer_get_bounds(root), utilities_window, &menu_section, 1, NULL);
  menu_layer_set_highlight_colors(simple_menu_layer_get_menu_layer(utilities),
      PBL_IF_COLOR_ELSE(GColorMidnightGreen, GColorBlack), GColorWhite);
  layer_add_child(root, simple_menu_layer_get_layer(utilities));
}

static void navigation_back(ClickRecognizerRef recognizer, void *context) {
  if (page == HOME) {
    window_stack_pop(true);
    return;
  }
  if (page == HISTORY) {
    page = history_return_page;
    window_stack_push(utilities_window, true);
  } else {
    page = page == HISTORY_DETAIL ? HISTORY : HOME;
  }
  notice_until = 0;
  layer_mark_dirty(display);
}

static void navigation_up(ClickRecognizerRef recognizer, void *context) {
  if (page == HISTORY || page == HISTORY_DETAIL) {
    if (history_selection > 0) history_selection--;
  } else if (page == SENSORS && received && state < 2 && time(NULL) - received <= 8) {
    page = WORKOUT;
  } else {
    page = SENSORS;
    notice_until = 0;
    sensor_request = 0;
  }
  layer_mark_dirty(display);
}

static void navigation_down(ClickRecognizerRef recognizer, void *context) {
  if (page == HISTORY || page == HISTORY_DETAIL) {
    if (history_selection + 1 < history_count) history_selection++;
  } else {
    simple_menu_layer_set_selected_index(utilities, 0, false);
    window_stack_push(utilities_window, true);
  }
  layer_mark_dirty(display);
}

static void click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, navigation_up);
  window_single_click_subscribe(BUTTON_ID_SELECT, open_options);
  window_single_click_subscribe(BUTTON_ID_DOWN, navigation_down);
  window_single_click_subscribe(BUTTON_ID_BACK, navigation_back);
}

static void outbox_sent(DictionaryIterator *iter, void *context) {
  sending = false;
  if (pending_command == START && starting) show_notice("Waiting for recording");
  if (pending_command == MEDIA_COMMAND) media_status("Sent to phone");
}

static void outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  sending = false;
  if (pending_command == MEDIA_COMMAND) {
    media_status("Phone unavailable");
  } else if (pending_command < REQUEST_SENSORS) {
    show_notice(starting ? "Check phone to start" : "Command not delivered");
    starting = false;
  }
}


static void label(GContext *ctx, const char *text, const char *font, GRect bounds) {
  graphics_draw_text(ctx, text, fonts_get_system_font(font), bounds,
      GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
}

static int clock_bottom(void) {
  return PBL_IF_ROUND_ELSE(49, 43);
}

static void draw_clock(GContext *ctx, int w) {
  const time_t now = time(NULL);
  const struct tm *local = localtime(&now);
  if (!local) return;

  const bool is_24h = clock_is_24h_style();
  char digits[6];
  const int hour = is_24h ? local->tm_hour : (local->tm_hour + 11) % 12 + 1;
  snprintf(digits, sizeof(digits), is_24h ? "%02d:%02d" : "%d:%02d", hour, local->tm_min);
  const GFont font = fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD);
  const int digits_width = graphics_text_layout_get_content_size(digits, font, GRect(0, 0, w, 48),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft).w;
  const int suffix_width = is_24h ? 0 : 21;
  const int width = digits_width + suffix_width;
  const int x = PBL_IF_ROUND_ELSE((w - digits_width) / 2, w - width - 5);
  const int y = PBL_IF_ROUND_ELSE(6, -1);
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, digits, font, GRect(x, y, digits_width + 1, 50),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  if (!is_24h) {
    const int suffix_x = PBL_IF_ROUND_ELSE(w * 3 / 4 + 6, x + digits_width + 2);
    const int suffix_y = y + PBL_IF_ROUND_ELSE(29, 24);
    label(ctx, local->tm_hour < 12 ? "AM" : "PM", FONT_KEY_GOTHIC_14_BOLD,
        GRect(suffix_x, suffix_y, suffix_width, 17));
  }
}

static void media_key(ClickRecognizerRef recognizer, void *context) {
  if (sending) {
    if (pending_command != MEDIA_COMMAND) media_status("Try again");
    return;
  }
  const ButtonId button = click_recognizer_get_button_id(recognizer);
  media_selected = button == BUTTON_ID_UP ? MEDIA_PREVIOUS :
      button == BUTTON_ID_DOWN ? MEDIA_NEXT : MEDIA_PLAY_PAUSE;
  media_status("Sending…");
  send_command(MEDIA_COMMAND, media_selected);
}

static void media_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, media_key);
  window_single_click_subscribe(BUTTON_ID_SELECT, media_key);
  window_single_click_subscribe(BUTTON_ID_DOWN, media_key);
}

static void transport_symbol(GContext *ctx, int x, int y, unsigned action) {
  if (action == MEDIA_PLAY_PAUSE) {
    graphics_fill_rect(ctx, GRect(x + 1, y - 7, 3, 14), 0, GCornerNone);
    graphics_fill_rect(ctx, GRect(x + 6, y - 7, 3, 14), 0, GCornerNone);
    x -= 5;
  }
  const int direction = action == MEDIA_PREVIOUS ? -1 : 1;
  GPoint points[] = { GPoint(x - 5 * direction, y - 7), GPoint(x + 5 * direction, y),
                     GPoint(x - 5 * direction, y + 7) };
  GPath path = { .num_points = ARRAY_LENGTH(points), .points = points };
  gpath_draw_filled(ctx, &path);
  if (action != MEDIA_PLAY_PAUSE)
    graphics_fill_rect(ctx, GRect(x + (direction == 1 ? 5 : -7), y - 7, 2, 14), 0, GCornerNone);
}

static void draw_media(Layer *layer, GContext *ctx) {
  const GSize size = layer_get_bounds(layer).size;
  const int w = size.w, h = size.h;
  draw_clock(ctx, w);
  label(ctx, "Media control", FONT_KEY_GOTHIC_24_BOLD, GRect(0, clock_bottom(), w, 28));
  const int y = h * 58 / 100;
  const unsigned actions[] = { MEDIA_PREVIOUS, MEDIA_PLAY_PAUSE, MEDIA_NEXT };
  const char *buttons[] = { "Up", "Select", "Down" };
  const bool feedback = media_notice && time(NULL) < media_notice_until;
  for (unsigned i = 0; i < ARRAY_LENGTH(actions); ++i) {
    const int x = w / 2 + ((int)i - 1) * PBL_IF_ROUND_ELSE(w * 27 / 100, w * 3 / 10);
    const bool center = actions[i] == MEDIA_PLAY_PAUSE;
    const bool selected = feedback && media_selected == actions[i];
    const int radius = center ? 21 : 16;
    if (center || selected) {
      graphics_context_set_fill_color(ctx, center ? PBL_IF_COLOR_ELSE(GColorMintGreen, GColorWhite) :
          PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorWhite));
      graphics_fill_circle(ctx, GPoint(x, y), radius);
    } else {
      graphics_context_set_stroke_color(ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
      graphics_draw_circle(ctx, GPoint(x, y), radius);
    }
    graphics_context_set_fill_color(ctx, center ? GColorBlack :
        selected ? PBL_IF_COLOR_ELSE(GColorWhite, GColorBlack) : GColorWhite);
    transport_symbol(ctx, x, y, actions[i]);
    label(ctx, buttons[i], FONT_KEY_GOTHIC_18_BOLD, GRect(x - 24, y + 25, 48, 23));
  }
  if (feedback)
    label(ctx, media_notice, FONT_KEY_GOTHIC_14_BOLD, GRect(w / 8, h - PBL_IF_ROUND_ELSE(25, 19), w * 3 / 4, 17));
}

static void open_media(void) {
  media_notice = NULL;
  if (!media_window) {
    media_window = window_create();
    window_set_background_color(media_window, GColorBlack);
    window_set_click_config_provider(media_window, media_click_config);
    Layer *root = window_get_root_layer(media_window);
    media_display = layer_create(layer_get_bounds(root));
    layer_set_update_proc(media_display, draw_media);
    layer_add_child(root, media_display);
  }
  window_stack_push(media_window, true);
}

static void panel(GContext *ctx, GRect box, bool emphasized) {
  graphics_context_set_fill_color(ctx, PBL_IF_COLOR_ELSE(
      emphasized ? GColorMidnightGreen : GColorOxfordBlue, GColorBlack));
  graphics_fill_rect(ctx, box, 6, GCornersAll);
#if !defined(PBL_COLOR)
  graphics_context_set_stroke_color(ctx, GColorWhite);
  graphics_draw_round_rect(ctx, box, 6);
#endif
}

static void metric_card(GContext *ctx, GRect box, const char *name, const char *value) {
  panel(ctx, box, false);
  graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorVividCerulean, GColorWhite));
  label(ctx, name, FONT_KEY_GOTHIC_14_BOLD, GRect(box.origin.x, box.origin.y - 1, box.size.w, 17));
  graphics_context_set_text_color(ctx, GColorWhite);
  label(ctx, value, FONT_KEY_GOTHIC_18_BOLD, GRect(box.origin.x, box.origin.y + 11, box.size.w, 22));
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
      GRect(inset, clock_bottom(), w - 2 * inset, 20));
  graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorLightGray, GColorWhite));
  snprintf(text, sizeof(text), "%s  /  %lu %s", clock, (unsigned long)count, count == 1 ? "set" : "sets");
  label(ctx, text, FONT_KEY_GOTHIC_18, GRect(0, clock_bottom() + 17, w, 22));

  bool resting = live && rest > 0 && rest < 601;
  bool complete = live && rest == 601;
  int panel_top = clock_bottom() + 39;
  panel(ctx, GRect(inset, panel_top, w - 2 * inset, 49), resting || complete);
  if (resting || complete) {
    time_t age = time(NULL) - received;
    unsigned left = resting && age >= 0 && (uint32_t)age < rest ? rest - (uint32_t)age : 0;
    snprintf(text, sizeof(text), "%u:%02u", left / 60, left % 60);
    graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(left ? GColorElectricBlue : GColorMintGreen, GColorWhite));
    label(ctx, text, FONT_KEY_GOTHIC_28_BOLD, GRect(0, panel_top - 1, w, 34));
    if (heart) snprintf(text, sizeof(text), "%s · %lu bpm", left ? "Rest" : "Ready", (unsigned long)heart);
    else snprintf(text, sizeof(text), "%s", left ? "Rest" : "Rest complete");
    label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, panel_top + 27, w, 22));
  } else {
    graphics_context_set_text_color(ctx, active_zone ? zone_color(active_zone - 1) : GColorWhite);
    if (live && heart) snprintf(text, sizeof(text), "%lu", (unsigned long)heart);
    else snprintf(text, sizeof(text), "--");
    label(ctx, text, FONT_KEY_GOTHIC_28_BOLD, GRect(0, panel_top - 1, w, 34));
    if (status) snprintf(text, sizeof(text), "%s", status);
    else if (active_zone > 1) snprintf(text, sizeof(text), "%s  /  Zone %u", wrist_requested ? "Watch bpm" : "bpm", active_zone - 1);
    else if (active_zone == 1) snprintf(text, sizeof(text), "%s  /  Below Z1", wrist_requested ? "Watch bpm" : "bpm");
    else if (live && heart && wrist_requested) snprintf(text, sizeof(text), "Watch bpm");
    else snprintf(text, sizeof(text), "Heart rate");
    label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, panel_top + 27, w, 22));
  }
  draw_gauge(ctx, inset, h - PBL_IF_ROUND_ELSE(38, 31), w - 2 * inset, active_zone);
}

static void home_row(GContext *ctx, int w, int y, const char *button, const char *name, bool primary) {
  int inset = PBL_IF_ROUND_ELSE(w / 7, 10);
  graphics_context_set_fill_color(ctx, primary ? PBL_IF_COLOR_ELSE(GColorMidnightGreen, GColorWhite) :
      PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorBlack));
  graphics_fill_rect(ctx, GRect(inset, y, w - 2 * inset, 25), 5, GCornersAll);
  graphics_context_set_text_color(ctx, primary ? PBL_IF_COLOR_ELSE(GColorMintGreen, GColorBlack) : GColorWhite);
  label(ctx, button, FONT_KEY_GOTHIC_14_BOLD, GRect(inset + 2, y + 4, 46, 18));
  graphics_draw_text(ctx, name, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD), GRect(inset + 52, y, w - 2 * inset - 54, 23),
      GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft, NULL);
  graphics_context_set_text_color(ctx, GColorWhite);
}

static void draw_home(GContext *ctx, int w, int h) {
  label(ctx, "Pebble Health", FONT_KEY_GOTHIC_28_BOLD, GRect(0, clock_bottom() + 2, w, 34));
  const int top = h * 47 / 100;
  const int gap = PBL_IF_ROUND_ELSE(25, (h - top - 29) / 2);
  home_row(ctx, w, top, "Up", "Live stats", false);
  home_row(ctx, w, top + gap, "Select", "Workouts", true);
  home_row(ctx, w, top + 2 * gap, "Down", "Menu", false);
}

static void draw_sensors(GContext *ctx, int w, int h) {
  const time_t now = time(NULL);
  bool linked = sensor_received && now - sensor_received <= 20;
  bool fresh = received && now - received <= 8 && state == 0;
  unsigned bpm = fresh && heart ? heart : linked && now - sensor_received <= 12 ? sensor_bpm : 0;
  bool wrist_preview = !bpm && wrist_bpm && now - wrist_received <= 12;
  if (wrist_preview) bpm = wrist_bpm;
  unsigned strap = sensor_status & 15, gps = (sensor_status >> 4) & 15;
  unsigned recording = (sensor_status >> 8) & 15;
  unsigned battery_level = (sensor_status >> 16) & 255;
  const char *strap_text[] = {"No strap selected", "Strap saved · idle", "Connecting strap…", "Strap connected", "Strap active", "Watch heart rate"};
  const char *gps_text[] = {"GPS off", "GPS ready", "Finding GPS…", "GPS fix", "GPS paused", "GPS unavailable"};
  char text[32];

  if (bpm) snprintf(text, sizeof(text), "%u bpm", bpm);
  else snprintf(text, sizeof(text), "-- bpm");
  graphics_context_set_text_color(ctx, bpm ? PBL_IF_COLOR_ELSE(GColorMintGreen, GColorWhite) : GColorWhite);
  label(ctx, text, FONT_KEY_GOTHIC_28_BOLD, GRect(0, clock_bottom() + 1, w, 34));
  graphics_context_set_text_color(ctx, GColorWhite);
  label(ctx, wrist_preview ? "Watch heart rate" : bpm ? "Heart rate" : wrist_sampling ? "Reading wrist…" : "Wrist HR unavailable",
      FONT_KEY_GOTHIC_18, GRect(0, clock_bottom() + 31, w, 22));
  if (linked && strap < 6) {
    int inset = PBL_IF_ROUND_ELSE(w / 7, 10);
    int strap_top = h * 58 / 100, gps_top = h * 72 / 100;
    panel(ctx, GRect(inset, strap_top, w - 2 * inset, 22), strap >= 3);
    panel(ctx, GRect(inset, gps_top, w - 2 * inset, 22), gps == 3);
    if (battery_level && strap != 5) snprintf(text, sizeof(text), "%s · %u%%", strap_text[strap], battery_level - 1);
    else snprintf(text, sizeof(text), "%s", strap_text[strap]);
    label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, strap_top - 1, w, 23));
    if (gps < 6) label(ctx, gps_text[gps], FONT_KEY_GOTHIC_18_BOLD, GRect(0, gps_top - 1, w, 24));
  } else {
    label(ctx, "Waiting for phone", FONT_KEY_GOTHIC_18, GRect(0, h * 63 / 100, w, 24));
  }
  const char *hint = !linked ? "Open Pebble Health" : recording == 1 || recording == 2 ? "Up  /  Workout" :
      recording == 3 ? "Phone ready to start" : "Phone linked";
  label(ctx, hint, FONT_KEY_GOTHIC_14, GRect(0, h * 87 / 100, w, 19));
}

static uint32_t history_value(const uint8_t *bytes) {
  return (uint32_t)bytes[0] | (uint32_t)bytes[1] << 8 |
      (uint32_t)bytes[2] << 16 | (uint32_t)bytes[3] << 24;
}

static void history_duration(uint32_t seconds, char *out, size_t size) {
  if (seconds >= 3600) snprintf(out, size, "%luh %02lum", (unsigned long)(seconds / 3600),
      (unsigned long)(seconds / 60 % 60));
  else snprintf(out, size, "%lu:%02lu", (unsigned long)(seconds / 60), (unsigned long)(seconds % 60));
}

static void history_date(const uint8_t *row, char *out, size_t size, const char *format) {
  time_t start = history_value(row + 1);
  struct tm *local = localtime(&start);
  if (local) strftime(out, size, format, local);
  else out[0] = 0;
}

static void draw_history(GContext *ctx, int w, int h) {
  int inset = PBL_IF_ROUND_ELSE(w / 8, 8);
  label(ctx, "History", FONT_KEY_GOTHIC_24_BOLD, GRect(0, clock_bottom(), w, 30));
  if (!history_count) {
    const char *message = history_answered ? "No saved workouts" :
        history_request && time(NULL) - history_request <= 8 ? "Loading workouts…" : "Open Pebble Health";
    label(ctx, message, FONT_KEY_GOTHIC_18, GRect(0, h / 2, w, 28));
    return;
  }

  char text[40], date[18], duration[18];
  if (page == HISTORY_DETAIL) {
    const uint8_t *row = history + history_selection * HISTORY_ROW;
    unsigned sport = row[0];
    const char *name = sport < ARRAY_LENGTH(sports) ? sports[sport] : "Workout";
    label(ctx, name, FONT_KEY_GOTHIC_28_BOLD, GRect(0, h * 39 / 100, w, 31));
    history_date(row, date, sizeof(date), "%d %b %H:%M");
    label(ctx, date, FONT_KEY_GOTHIC_18, GRect(0, h * 56 / 100, w, 25));
    history_duration(history_value(row + 5), duration, sizeof(duration));
    label(ctx, duration, FONT_KEY_GOTHIC_24_BOLD, GRect(0, h * 66 / 100, w, 29));
    uint32_t distance = history_value(row + 9);
    if (distance != UINT32_MAX) {
      snprintf(text, sizeof(text), "%lu.%02lu km", (unsigned long)(distance / 1000),
          (unsigned long)(distance % 1000 / 10));
      label(ctx, text, FONT_KEY_GOTHIC_18, GRect(0, h * 81 / 100, w, 21));
    }
    return;
  }

  unsigned first = history_selection > 1 ? history_selection - 1 : 0;
  if (history_count > 3 && first > history_count - 3) first = history_count - 3;
  const int list_top = clock_bottom() + 29;
  const int row_h = (h - list_top - PBL_IF_ROUND_ELSE(25, 17)) / 3;
  for (unsigned i = first; i < first + 3 && i < history_count; i++) {
    const uint8_t *row = history + i * HISTORY_ROW;
    unsigned sport = row[0];
    int y = list_top + (i - first) * row_h;
    bool selected = i == history_selection;
    graphics_context_set_fill_color(ctx, selected ? PBL_IF_COLOR_ELSE(GColorMidnightGreen, GColorWhite) :
        PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorBlack));
    graphics_fill_rect(ctx, GRect(inset, y, w - 2 * inset, row_h - 3), 5, GCornersAll);
    if (selected) graphics_context_set_text_color(ctx, PBL_IF_COLOR_ELSE(GColorMintGreen, GColorBlack));
    label(ctx, sport < ARRAY_LENGTH(sports) ? sports[sport] : "Workout", FONT_KEY_GOTHIC_18_BOLD,
        GRect(inset, y - 1, w - 2 * inset, 19));
    history_date(row, date, sizeof(date), "%d %b");
    history_duration(history_value(row + 5), duration, sizeof(duration));
    snprintf(text, sizeof(text), "%s  /  %s", date, duration);
    label(ctx, text, FONT_KEY_GOTHIC_14, GRect(inset, y + 12, w - 2 * inset, 17));
    graphics_context_set_text_color(ctx, GColorWhite);
  }
  snprintf(text, sizeof(text), "%u / %u%s", history_selection + 1, history_count, history_answered ? "" : "  ·  Cached");
  label(ctx, text, FONT_KEY_GOTHIC_14, GRect(0, list_top + 3 * row_h, w, 17));
}

static void draw(Layer *layer, GContext *ctx) {
  GRect frame = layer_get_bounds(layer);
  int w = frame.size.w, h = frame.size.h;
  bool stale = !received || time(NULL) - received > 8;
  bool live = !stale && state == 0;
  graphics_context_set_text_color(ctx, GColorWhite);
  draw_clock(ctx, w);
  char text[32];
  if (notice && time(NULL) < notice_until) {
    label(ctx, notice, FONT_KEY_GOTHIC_18_BOLD, GRect(w / 12, h * 38 / 100, w * 5 / 6, 28));
    return;
  }
  if (page == SENSORS) { draw_sensors(ctx, w, h); return; }
  if (page == HISTORY || page == HISTORY_DETAIL) { draw_history(ctx, w, h); return; }
  if (page == HOME || !received) { draw_home(ctx, w, h); return; }

  const char *status = state == 3 ? "Discarded" : state == 2 ? "Finished" : stale ? "Signal lost" : state == 1 ? "Paused" : NULL;
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
  const int banner_top = clock_bottom() + 1;
  panel(ctx, GRect(w / 8, banner_top, w * 3 / 4, 22), live);
  graphics_context_set_text_color(ctx, live ? PBL_IF_COLOR_ELSE(GColorMintGreen, GColorWhite) : GColorWhite);
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, banner_top - 1, w, 23));

  if (live && heart) snprintf(text, sizeof(text), "%lu", (unsigned long)heart);
  else snprintf(text, sizeof(text), "--");
  graphics_context_set_text_color(ctx, active ? zone_color(active - 1) : GColorWhite);
  label(ctx, text, FONT_KEY_GOTHIC_28_BOLD, GRect(0, banner_top + 18, w, 34));
  if (active > 1) snprintf(text, sizeof(text), "%s  /  Zone %u", wrist_requested ? "Watch bpm" : "bpm", active - 1);
  else if (active == 1) snprintf(text, sizeof(text), "%s  /  Below Z1", wrist_requested ? "Watch bpm" : "bpm");
  else if (live && heart && wrist_requested) snprintf(text, sizeof(text), "Watch bpm");
  else if (live && heart) snprintf(text, sizeof(text), "bpm  /  Set max HR");
  else snprintf(text, sizeof(text), "Heart rate");
  label(ctx, text, FONT_KEY_GOTHIC_18_BOLD, GRect(0, banner_top + 41, w, 23));

  draw_gauge(ctx, w / 10, banner_top + 62, w - 2 * (w / 10), active);
  int left = PBL_IF_ROUND_ELSE(w * 18 / 100, 7);
  int metric_width = w / 2 - left - 2;
  int metric_top = PBL_IF_ROUND_ELSE(h - 41, h >= 200 ? h * 73 / 100 : h - 32);
  int metric_height = PBL_IF_ROUND_ELSE(29, h >= 200 ? 36 : 31);
  if (mode == 3) {
    snprintf(text, sizeof(text), "%lu %s", (unsigned long)count, count == 1 ? "lap" : "laps");
    metric_card(ctx, GRect(left, metric_top, w - 2 * left, metric_height), title[0] ? title : "Workout", text);
    return;
  }
  snprintf(text, sizeof(text), "%lu.%02lu km", (unsigned long)(meters / 1000), (unsigned long)(meters % 1000 / 10));
  metric_card(ctx, GRect(left, metric_top, metric_width, metric_height), "Distance", text);
  if (mode == 2) {
    if (live && pace) snprintf(text, sizeof(text), "%lu km/h", (unsigned long)((3600 + pace / 2) / pace));
    else snprintf(text, sizeof(text), "-- km/h");
  } else if (live && pace) {
    snprintf(text, sizeof(text), "%lu:%02lu/km", (unsigned long)(pace / 60), (unsigned long)(pace % 60));
  } else snprintf(text, sizeof(text), "-- /km");
  metric_card(ctx, GRect(w / 2 + 2, metric_top, metric_width, metric_height), mode == 2 ? "Speed" : "Pace", text);
  if (battery && !stale && h >= 200) {
    snprintf(text, sizeof(text), "Strap %lu%%", (unsigned long)(battery - 1));
    label(ctx, text, FONT_KEY_GOTHIC_14, GRect(0, h * 90 / 100, w, 17));
  }
}

static void inbox(DictionaryIterator *iter, void *context) {
  Tuple *status_reply = dict_find(iter, 24);
  if (status_reply && status_reply->type == TUPLE_UINT && status_reply->length == 4) {
    sensor_status = status_reply->value->uint32;
    Tuple *bpm = dict_find(iter, 25);
    sensor_bpm = bpm && bpm->type == TUPLE_UINT && bpm->length == 4 ? bpm->value->uint32 : 0;
    sensor_received = time(NULL);
    layer_mark_dirty(display);
    return;
  }
  Tuple *count_reply = dict_find(iter, 27);
  if (count_reply && count_reply->type == TUPLE_UINT && count_reply->length == 4) {
    unsigned rows = count_reply->value->uint32;
    Tuple *payload = dict_find(iter, 26);
    if (rows <= HISTORY_LIMIT && (!rows ||
        (payload && payload->type == TUPLE_BYTE_ARRAY && payload->length == rows * HISTORY_ROW))) {
      if (rows) {
        if (rows != history_count || memcmp(history, payload->value->data, rows * HISTORY_ROW)) {
          memcpy(history, payload->value->data, rows * HISTORY_ROW);
          persist_write_data(HISTORY_KEY, history, rows * HISTORY_ROW);
        }
      } else if (history_count) {
        persist_delete(HISTORY_KEY);
      }
      history_count = rows;
      if (history_selection >= rows) history_selection = rows ? rows - 1 : 0;
      if (!rows && page == HISTORY_DETAIL) page = HISTORY;
      history_answered = true;
      layer_mark_dirty(display);
    }
    return;
  }
  const bool first_update = !received;
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
  Tuple *session = dict_find(iter, TOKEN);
  has_token = session && session->type == TUPLE_UINT && session->length == 4;
  if (has_token) token = session->value->uint32 & SESSION_TOKEN_MASK;
  wrist_requested = has_token && state == 0 && (session->value->uint32 & HEART_REQUEST_BIT);
  if (starting || (first_update && page == HOME && state < 2)) {
    page = WORKOUT;
    starting = false;
    notice_until = 0;
  }
  received = time(NULL);
  update_wrist_sampling(received);
#if defined(PBL_HEALTH)
  if (app_worker_is_running()) {
    AppWorkerMessage message = { .data0 = state < 2 };
    app_worker_send_message(DETECTION_PHONE_RECORDING, &message);
  }
  if (state < 2 && menu_type == DETECTED_WORKOUT &&
      window_stack_get_top_window() == options_window) window_stack_pop(true);
#endif
  layer_mark_dirty(display);
}

static void tick(struct tm *current_time, TimeUnits units) {
  const time_t clock = time(NULL);
  update_wrist_sampling(clock);
#if defined(PBL_HEALTH)
  if (detection_pending && !window_stack_contains_window(utilities_window)) offer_detection();
#endif
  if (!sending && page == SENSORS && clock - sensor_request >= 8) {
    sensor_request = clock;
    send_command(REQUEST_SENSORS, 0);
  }
  if (!sending && page == HISTORY && !history_request) {
    history_request = clock;
    send_command(REQUEST_HISTORY, 0);
  }
  layer_mark_dirty(display);
  if (media_display) layer_mark_dirty(media_display);
}

int main(void) {
  time_t now;
  uint16_t millis;
  time_ms(&now, &millis);
  sequence = (uint32_t)now * 1000 + millis;
  window = window_create();
  options_window = window_create();
  create_utilities();
  window_set_click_config_provider(window, click_config);
  window_set_background_color(window, GColorBlack);
  Layer *root = window_get_root_layer(window);
  display = layer_create(layer_get_bounds(root));
  layer_set_update_proc(display, draw);
  layer_add_child(root, display);
  window_stack_push(window, false);
  const int cached = persist_read_data(HISTORY_KEY, history, sizeof(history));
  if (cached > 0 && (size_t)cached <= sizeof(history) && cached % HISTORY_ROW == 0)
    history_count = cached / HISTORY_ROW;
  app_message_register_inbox_received(inbox);
  app_message_register_outbox_sent(outbox_sent);
  app_message_register_outbox_failed(outbox_failed);
  app_message_open(128, 48);
  tick_timer_service_subscribe(SECOND_UNIT, tick);
#if defined(PBL_HEALTH)
  app_worker_message_subscribe(detection_message);
  health_service_events_subscribe(on_watch_heart, NULL);
  if (launch_reason() == APP_LAUNCH_WORKER) offer_detection();
#endif
  app_event_loop();
#if defined(PBL_HEALTH)
  app_worker_message_unsubscribe();
  set_wrist_sampling(false);
  health_service_events_unsubscribe();
#endif
  tick_timer_service_unsubscribe();
  app_message_deregister_callbacks();
  if (options) simple_menu_layer_destroy(options);
  window_destroy(options_window);
  simple_menu_layer_destroy(utilities);
  window_destroy(utilities_window);
  layer_destroy(display);
  window_destroy(window);
  if (media_window) {
    layer_destroy(media_display);
    window_destroy(media_window);
  }
}
