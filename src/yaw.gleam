import datastar_gleam/event
import datastar_gleam/mist as datastar_mist
import envoy
import gleam/bytes_tree
import gleam/erlang/process
import gleam/http.{Get}
import gleam/http/request.{type Request}
import gleam/http/response
import gleam/int
import gleam/io
import gleam/list
import gleam/option.{type Option, None, Some}
import gleam/otp/actor
import gleam/string
import mist

const now_lines = [
  "Shipping small web software with Gleam and HTML-first interactions.",
  "Refining local-first workflows instead of adding more cloud ceremony.",
  "Collecting notes on interface writing, product edges, and calm tooling.",
  "Keeping the stack compact enough to understand in one sitting.",
]

const profile_metrics = [
  #("Base", "US West", "Mostly building from a local dev shell."),
  #(
    "Focus",
    "Web systems",
    "Product infrastructure, UI composition, and maintainable tooling.",
  ),
  #(
    "Stack",
    "Gleam",
    "Mist, Datastar, and plain CSS over a large client build.",
  ),
]

const project_cards = [
  #(
    "01",
    "Personal Publishing",
    "Essays, working notes, and experiments published from a codebase small enough to revise quickly.",
  ),
  #(
    "02",
    "Product Prototypes",
    "Fast server-rendered prototypes for testing interaction ideas before committing to larger architecture.",
  ),
  #(
    "03",
    "Developer Tooling",
    "Scripts, environments, and internal interfaces that reduce friction instead of layering abstraction.",
  ),
]

const note_items = [
  #(
    "Writing software that stays legible",
    "Keeping the page, the routes, and the state transitions close enough that maintenance feels obvious.",
  ),
  #(
    "HTML over indirection",
    "Choosing server-rendered fragments and direct links unless complexity clearly justifies something heavier.",
  ),
  #(
    "Design with a point of view",
    "Using sharp type, hard borders, and deliberate contrast so the site feels authored instead of templated.",
  ),
]

type BskyProfile {
  BskyProfile(handle: String, display_name: String)
}

type BskyPost {
  BskyPost(
    uri: String,
    text: String,
    indexed_at: String,
    handle: String,
    display_name: String,
  )
}

const styles = "
:root {
  --paper: #f3d33b;
  --ink: #121212;
  --edge: #121212;
  --muted: rgba(18, 18, 18, 0.68);
  --panel: rgba(255, 244, 160, 0.82);
  --accent: #121212;
}

* { box-sizing: border-box; }

html {
  color: var(--ink);
  font-family: Iowan Old Style, Palatino Linotype, Book Antiqua, URW Palladio L, serif;
  line-height: 1.35;
  scroll-behavior: smooth;
}

body {
  margin: 0;
  min-height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(255,255,255,0.34), transparent 32rem),
    linear-gradient(180deg, #f7dc57 0%, var(--paper) 58%, #efcb23 100%);
}

a {
  color: inherit;
  text-decoration-thickness: 0.11em;
  text-underline-offset: 0.18em;
}

.page {
  width: min(1120px, calc(100vw - 3.25rem));
  margin: 0 auto;
  padding: 1.2rem 0 3.5rem;
}

.masthead {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: baseline;
  border-bottom: 3px solid var(--edge);
  padding-bottom: 0.75rem;
  margin-bottom: 1.6rem;
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  font-size: 0.9rem;
}

.masthead nav {
  display: flex;
  gap: 0.9rem;
  flex-wrap: wrap;
}

.masthead a {
  text-decoration: none;
}

.masthead a[aria-current=\"page\"] {
  border-bottom: 3px solid var(--edge);
  padding-bottom: 0.15rem;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(17rem, 0.85fr);
  gap: 1.2rem;
  align-items: start;
}

.hero-card, .aside-card, .feature, .footer-box, .stream-box, .quote-box {
  border: 3px solid var(--edge);
  background: var(--panel);
  box-shadow: 0.45rem 0.45rem 0 0 var(--edge);
  min-width: 0;
}

.hero-card {
  padding: 1.3rem 1.25rem 1.5rem;
}

.eyebrow, .kicker, .metric-label {
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--accent);
}

h1 {
  margin: 0.35rem 0 0.8rem;
  font-size: clamp(2.8rem, 8vw, 6.2rem);
  line-height: 0.94;
  letter-spacing: -0.05em;
}

.lede {
  max-width: 36rem;
  margin: 0;
  font-size: clamp(1.05rem, 2vw, 1.35rem);
}

.cta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.8rem;
  margin: 1.25rem 0 1.15rem;
}

.button {
  appearance: none;
  border: 3px solid var(--edge);
  background: var(--ink);
  color: var(--paper);
  padding: 0.72rem 1rem;
  font: inherit;
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  text-decoration: none;
  cursor: pointer;
  box-shadow: 0.3rem 0.3rem 0 0 rgba(18, 18, 18, 0.18);
}

.button.alt {
  background: transparent;
  color: var(--ink);
}

.quote-box, .aside-card, .footer-box, .stream-box {
  padding: 1.05rem 1rem 1.1rem;
}

.quote-box {
  margin-top: 1.25rem;
}

.metric + .metric {
  margin-top: 0.95rem;
  padding-top: 0.95rem;
  border-top: 2px solid rgba(18, 18, 18, 0.16);
}

.metric-value, .section-title {
  margin-top: 0.2rem;
  font-size: clamp(1.45rem, 4vw, 2rem);
  line-height: 1.02;
}

.muted {
  color: var(--muted);
}

.hero, .stream-section, .notes-grid, .features, .footer-grid, .feed-grid {
  padding-bottom: 0.8rem;
}

.page > section + section {
  margin-top: 1.6rem;
}

.stream-section, .notes-grid, .footer-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(16rem, 0.85fr);
  gap: 1rem;
}

.features {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.feature {
  padding: 1rem 1rem 1.1rem;
}

.feature h2, .note-item h3 {
  margin: 0.4rem 0 0.45rem;
  font-size: 1.35rem;
}

.stream-box .line {
  display: block;
  font-size: 1.05rem;
  padding: 0.25rem 0;
}

.bsky-list {
  display: grid;
  gap: 0.9rem;
  margin-top: 0.6rem;
}

.bsky-post {
  border-top: 2px solid rgba(18, 18, 18, 0.18);
  padding-top: 0.75rem;
}

.bsky-post:first-child {
  border-top: 0;
  padding-top: 0;
}

.bsky-meta {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: baseline;
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.bsky-post p {
  margin: 0.35rem 0 0;
  white-space: pre-wrap;
}

.notes-grid {
  grid-template-columns: minmax(0, 1fr) minmax(17rem, 0.9fr);
}

.feed-grid {
  display: grid;
  grid-template-columns: minmax(0, 2.2fr) minmax(15rem, 0.42fr);
  gap: 1rem;
}

.notes-list {
  display: grid;
  gap: 1rem;
}

.note-item {
  border-top: 2px solid rgba(18, 18, 18, 0.16);
  padding-top: 0.9rem;
}

.note-item:first-child {
  border-top: 0;
  padding-top: 0.1rem;
}

.section-copy, .footer-box p, .aside-card p, .feature p {
  margin-bottom: 0;
}

code, .mono {
  font-family: IBM Plex Mono, ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.92rem;
}

@media (max-width: 980px) {
  .hero, .stream-section, .notes-grid, .feed-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .features, .footer-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 840px) {
  .hero, .stream-section, .features, .footer-grid, .notes-grid, .feed-grid {
    grid-template-columns: 1fr;
  }

  .page {
    width: min(calc(100vw - 2rem), 1080px);
  }

  h1 {
    font-size: clamp(2.7rem, 18vw, 4.9rem);
  }
}
"

const now_controller_script = "
window.yawNow = (function () {
  let source = null;

  function currentPanel() {
    return document.getElementById('now-panel');
  }

  function setStatus(text) {
    const node = document.querySelector('#now-panel .now-status');
    if (node) node.textContent = text;
  }

  function formatTimestamp(node) {
    const value = node.getAttribute('datetime');
    if (!value) return;

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return;

    node.textContent = new Intl.DateTimeFormat(navigator.language, {
      dateStyle: 'medium',
      timeStyle: 'short'
    }).format(date);
  }

  function formatTimestamps(root) {
    (root || document).querySelectorAll('time[data-local-timestamp]').forEach(formatTimestamp);
  }

  function replacePanel(html) {
    const next = new DOMParser().parseFromString(html, 'text/html').body.firstElementChild;
    const current = currentPanel();
    if (current && next) {
      current.replaceWith(next);
      formatTimestamps(next);
    }
  }

  function stop(text) {
    if (source) {
      source.close();
      source = null;
    }
    if (text) setStatus(text);
  }

  function refresh() {
    stop('Refreshing...');
    source = new EventSource('/streams/now');
    source.addEventListener('datastar-patch-elements', function () {
      stop('Updated just now.');
    });
    source.onerror = function () {
      stop('Refresh interrupted. Try again.');
    };
  }

  document.addEventListener('DOMContentLoaded', function () {
    formatTimestamps(document);
  });

  return { refresh, formatTimestamps };
}());
"

type NowMessage {
  PushLine(Int)
}

@external(erlang, "yaw_ffi", "lookup_env")
fn lookup_env(name: String) -> Result(String, Nil)

@external(erlang, "yaw_ffi", "fetch_bsky_profile")
fn fetch_bsky_profile_raw(handle: String) -> Result(#(String, String), Nil)

@external(erlang, "yaw_ffi", "fetch_bsky_posts")
fn fetch_bsky_posts_raw(
  handle: String,
) -> List(#(String, String, String, String, String))

pub fn main() -> Nil {
  let port = read_port()

  let assert Ok(_) =
    handle_request
    |> mist.new
    |> mist.bind("0.0.0.0")
    |> mist.port(port)
    |> mist.start

  io.println("Yaw running on http://localhost:" <> int.to_string(port))
  process.sleep_forever()
}

fn read_port() -> Int {
  case envoy.get("PORT") {
    Ok(raw) ->
      case int.parse(raw) {
        Ok(port) -> port
        Error(_) -> 8080
      }
    Error(_) -> 8080
  }
}

fn handle_request(
  req: Request(mist.Connection),
) -> response.Response(mist.ResponseData) {
  case req.method, req.path {
    Get, "/" ->
      html_response(
        layout("Home", [
          bluesky_section(),
          hero_section(),
          work_section(),
          now_section(),
          notes_section(),
          contact_section(),
        ]),
      )
    Get, "/work" ->
      html_response(
        layout("Work", [hero_section(), work_section(), now_section()]),
      )
    Get, "/notes" ->
      html_response(
        layout("Notes", [bluesky_section(), hero_section(), notes_section()]),
      )
    Get, "/contact" ->
      html_response(layout("Contact", [hero_section(), contact_section()]))
    Get, "/favicon.ico" -> favicon_response()
    Get, "/streams/now" -> now_stream(req)
    _, _ -> not_found_response()
  }
}

fn html_response(body: String) -> response.Response(mist.ResponseData) {
  response.new(200)
  |> response.set_header("content-type", "text/html; charset=utf-8")
  |> response.set_body(mist.Bytes(bytes_tree.from_string(body)))
}

fn favicon_response() -> response.Response(mist.ResponseData) {
  response.new(204)
  |> response.set_header("content-type", "image/x-icon")
  |> response.set_body(mist.Bytes(bytes_tree.from_string("")))
}

fn not_found_response() -> response.Response(mist.ResponseData) {
  response.new(404)
  |> response.set_header("content-type", "text/plain; charset=utf-8")
  |> response.set_body(mist.Bytes(bytes_tree.from_string("Not found")))
}

fn layout(active: String, sections: List(String)) -> String {
  "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Nandi</title><style>"
  <> styles
  <> "</style><script>"
  <> now_controller_script
  <> "</script><script type=\"module\" src=\"https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.2/bundles/datastar.js\"></script></head><body><main class=\"page\"><header class=\"masthead\"><div>Nandi</div><nav>"
  <> nav_link(active, "/", "Home")
  <> nav_link(active, "/work", "Work")
  <> nav_link(active, "/notes", "Notes")
  <> nav_link(active, "/contact", "Contact")
  <> "</nav></header>"
  <> string.concat(sections)
  <> "</main></body></html>"
}

fn nav_link(active: String, href: String, label: String) -> String {
  let current = case active == label {
    True -> " aria-current=\"page\""
    False -> ""
  }
  "<a href=\"" <> href <> "\"" <> current <> ">" <> label <> "</a>"
}

fn hero_section() -> String {
  "<section class=\"hero\"><article class=\"hero-card\"><div class=\"eyebrow\">Personal Website</div><h1>Building quiet software with a strong point of view.</h1><p class=\"lede\">I work on web products, developer systems, and interfaces that stay legible under real maintenance. This site is a compact home for projects, notes, and current work.</p><div class=\"cta-row\"><a class=\"button\" href=\"/contact\">Get in touch</a><button class=\"button alt\" type=\"button\" onclick=\"window.yawNow.refresh()\">Refresh now</button></div><div class=\"quote-box\"><div class=\"eyebrow\">Working Principle</div><blockquote>Fewer moving parts, stronger defaults, sharper writing.</blockquote><p class=\"muted\">The site stays intentionally small so changing it never feels expensive.</p></div></article><aside class=\"aside-card\"><div class=\"eyebrow\">At a glance</div>"
  <> render_metrics(profile_metrics)
  <> "</aside></section>"
}

fn render_metrics(metrics: List(#(String, String, String))) -> String {
  case metrics {
    [] -> ""
    [#(label, value, detail), ..rest] ->
      "<div class=\"metric\"><div class=\"metric-label\">"
      <> label
      <> "</div><div class=\"metric-value\">"
      <> value
      <> "</div><div class=\"muted\">"
      <> detail
      <> "</div></div>"
      <> render_metrics(rest)
  }
}

fn work_section() -> String {
  "<section class=\"features\">" <> render_cards(project_cards) <> "</section>"
}

fn bluesky_section() -> String {
  case bluesky_handle() {
    None ->
      "<section class=\"feed-grid\"><article class=\"footer-box\"><div class=\"eyebrow\">Bluesky</div><h2 class=\"section-title\">Recent posts</h2><p class=\"muted\">Set AT_HANDLE in .env to show recent Bluesky posts.</p></article><aside class=\"footer-box\"><div class=\"eyebrow\">Handle</div><p class=\"mono\">AT_HANDLE not set</p><p>Posts are fetched server-side from the public Bluesky API and cached briefly to keep page loads predictable.</p></aside></section>"
    Some(handle) -> {
      let profile = fetch_bsky_profile(handle)
      let posts = fetch_bsky_posts(handle)
      let shown_handle = profile.handle
      let display_name = profile.display_name
      "<section class=\"feed-grid\"><article class=\"footer-box\"><div class=\"eyebrow\">Bluesky</div><h2 class=\"section-title\">Recent posts</h2>"
      <> render_bsky_posts(posts, display_name)
      <> "</article><aside class=\"footer-box\"><div class=\"eyebrow\">Handle</div><p class=\"mono\">"
      <> escape_html(shown_handle)
      <> "</p><p>Posts are fetched server-side from the public Bluesky API and cached briefly to keep page loads predictable.</p></aside></section>"
    }
  }
}

fn render_cards(cards: List(#(String, String, String))) -> String {
  case cards {
    [] -> ""
    [#(kicker, title, body), ..rest] ->
      "<article class=\"feature\"><div class=\"kicker\">"
      <> kicker
      <> "</div><h2>"
      <> title
      <> "</h2><p>"
      <> body
      <> "</p></article>"
      <> render_cards(rest)
  }
}

fn render_bsky_posts(posts: List(BskyPost), display_name: String) -> String {
  case posts {
    [] -> "<p class=\"muted\">No recent posts found.</p>"
    _ ->
      "<div class=\"bsky-list\">"
      <> render_bsky_post_list(posts, display_name)
      <> "</div>"
  }
}

fn render_bsky_post_list(
  posts: List(BskyPost),
  display_name: String,
) -> String {
  case posts {
    [] -> ""
    [post, ..rest] -> {
      let BskyPost(
        uri:,
        text:,
        indexed_at:,
        handle:,
        display_name: post_display_name,
      ) = post
      let author = first_non_empty(post_display_name, display_name)
      let post_link = bsky_post_url(handle, uri)
      "<article class=\"bsky-post\"><div class=\"bsky-meta\"><strong>"
      <> escape_html(first_non_empty(author, handle))
      <> "</strong><time class=\"muted\">"
      <> escape_html(first_non_empty(indexed_at, "recent"))
      <> "</time></div><p>"
      <> escape_html(text)
      <> "</p>"
      <> render_bsky_link(post_link)
      <> "</article>"
      <> render_bsky_post_list(rest, display_name)
    }
  }
}

fn render_bsky_link(url: Option(String)) -> String {
  case url {
    Some(url) ->
      "<p><a class=\"mono\" href=\""
      <> escape_html(url)
      <> "\" target=\"_blank\" rel=\"noreferrer\">Open on Bluesky</a></p>"
    None -> ""
  }
}

fn now_section() -> String {
  "<section class=\"stream-section\"><article class=\"stream-box\"><div class=\"eyebrow\">Now</div>"
  <> now_panel(
    [
      "Shipping software with low ceremony and strong editorial structure.",
      "Documenting ideas in public as working notes instead of polished launch copy.",
    ],
    "Press “Refresh now” to pull a live update from the server.",
  )
  <> "</article><div class=\"footer-box\"><div class=\"eyebrow\">Approach</div><h2 class=\"section-title\">Built to be edited, not admired from a distance.</h2><p class=\"section-copy\">I prefer systems where content, behavior, and deployment stay close together. That usually means server-rendered pages, direct interfaces, and a bias toward tools that are easy to inspect.</p></div></section>"
}

fn notes_section() -> String {
  "<section class=\"notes-grid\"><article class=\"footer-box\"><div class=\"eyebrow\">Notes</div><h2 class=\"section-title\">Ongoing themes</h2><div class=\"notes-list\">"
  <> render_notes(note_items)
  <> "</div></article><div class=\"footer-box\"><div class=\"eyebrow\">Source</div><p>This site now runs as a single Gleam service with server-rendered HTML and a small Datastar hook for lightweight updates.</p><p class=\"mono\">devenv shell -- gleam run</p></div></section>"
}

fn bluesky_handle() -> Option(String) {
  case lookup_env("AT_HANDLE") {
    Ok(handle) ->
      case string.length(handle) > 0 {
        True -> Some(handle)
        False -> None
      }
    _ -> None
  }
}

fn fetch_bsky_profile(handle: String) -> BskyProfile {
  case fetch_bsky_profile_raw(handle) {
    Ok(#(profile_handle, display_name)) ->
      BskyProfile(
        handle: first_non_empty(profile_handle, handle),
        display_name: display_name,
      )
    Error(_) -> BskyProfile(handle: handle, display_name: "")
  }
}

fn fetch_bsky_posts(handle: String) -> List(BskyPost) {
  fetch_bsky_posts_raw(handle)
  |> list.map(fn(post) {
    let #(uri, text, indexed_at, post_handle, display_name) = post
    BskyPost(
      uri: uri,
      text: text,
      indexed_at: indexed_at,
      handle: first_non_empty(post_handle, handle),
      display_name: display_name,
    )
  })
}

fn bsky_post_url(handle: String, uri: String) -> Option(String) {
  case string.split(uri, "/") {
    ["at:", "", _, "app.bsky.feed.post", rkey] ->
      Some("https://bsky.app/profile/" <> handle <> "/post/" <> rkey)
    _ -> None
  }
}

fn first_non_empty(primary: String, fallback: String) -> String {
  case string.length(primary) > 0 {
    True -> primary
    False -> fallback
  }
}

fn escape_html(text: String) -> String {
  text
  |> string.replace(each: "&", with: "&amp;")
  |> string.replace(each: "<", with: "&lt;")
  |> string.replace(each: ">", with: "&gt;")
  |> string.replace(each: "\"", with: "&quot;")
  |> string.replace(each: "'", with: "&#39;")
}

fn render_notes(notes: List(#(String, String))) -> String {
  case notes {
    [] -> ""
    [#(title, body), ..rest] ->
      "<article class=\"note-item\"><h3>"
      <> title
      <> "</h3><p>"
      <> body
      <> "</p></article>"
      <> render_notes(rest)
  }
}

fn contact_section() -> String {
  "<section class=\"notes-grid\"><aside class=\"footer-box\"><div class=\"eyebrow\">Contact</div><h2 class=\"section-title\">Available for careful product and engineering work.</h2><p>If you need help shaping a web product, simplifying a delivery path, or tightening the feel of an interface, reach out.</p><p class=\"mono\">nandi@localhost</p><p class=\"mono\">github.com/nandi</p><p class=\"muted\">Replace these placeholders with your real contact points.</p></aside><div class=\"footer-box\"><div class=\"eyebrow\">Intent</div><p>The goal is a personal website that feels authored: compact, opinionated, and easy to keep current over time.</p><p>No feed clutter, no generic portfolio chrome, no split between content and implementation.</p></div></section>"
}

fn now_panel(lines: List(String), status: String) -> String {
  "<div id=\"now-panel\"><div id=\"now-items\">"
  <> render_lines(lines, 0)
  <> "</div><p class=\"muted now-status\">"
  <> status
  <> "</p></div>"
}

fn render_lines(lines: List(String), idx: Int) -> String {
  case lines {
    [] -> ""
    [line, ..rest] ->
      "<div class=\"line\" id=\"line-"
      <> int.to_string(idx)
      <> "\"><span class=\"mono\">"
      <> pad_two(idx + 1)
      <> "</span> "
      <> line
      <> "</div>"
      <> render_lines(rest, idx + 1)
  }
}

fn pad_two(value: Int) -> String {
  case value < 10 {
    True -> "0" <> int.to_string(value)
    False -> int.to_string(value)
  }
}

fn now_stream(
  req: Request(mist.Connection),
) -> response.Response(mist.ResponseData) {
  mist.server_sent_events(
    request: req,
    initial_response: response.new(200),
    init: fn(subject) {
      schedule_lines(subject, 0)
      0
    },
    loop: fn(state, message, conn) {
      case message {
        PushLine(index) -> {
          let lines = list.take(now_lines, index + 1)
          let status = case index + 1 == list.length(now_lines) {
            True -> "Updated just now."
            False -> "Receiving fresh lines..."
          }
          let patch =
            event.new_elements(now_panel(lines, status))
            |> event.with_selector("#now-panel")
            |> event.patch_elements_to_datastar_event

          let _ = datastar_mist.send_event(conn, patch)
          actor.continue(state)
        }
      }
    },
  )
}

fn schedule_lines(subject: process.Subject(NowMessage), index: Int) -> Nil {
  case index < list.length(now_lines) {
    True -> {
      let _ = process.send_after(subject, index * 450, PushLine(index))
      schedule_lines(subject, index + 1)
    }
    False -> Nil
  }
}
