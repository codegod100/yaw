(ns yaw.core
  (:import
   (java.net URI URLEncoder)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket WebSocket$Listener)
   (java.nio.charset StandardCharsets)
   (java.time Instant LocalTime ZoneId)
   (java.time.format DateTimeFormatter)
   (java.util.concurrent CompletableFuture LinkedBlockingDeque TimeUnit))
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [hiccup2.core :as h]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as ring]
   [ring.util.response :as response]
   [starfederation.datastar.clojure.api.sse :as dsse]
   [starfederation.datastar.clojure.adapter.http-kit :refer [->sse-response on-open]]
   [starfederation.datastar.clojure.api :as d*]))

(def now-lines
  ["Shipping small web software with Clojure and HTML-first interactions."
   "Refining local-first workflows instead of adding more cloud ceremony."
   "Collecting notes on interface writing, product edges, and calm tooling."
   "Keeping the stack compact enough to understand in one sitting."])

(def profile-metrics
  [{:label "Base" :value "US West" :detail "Mostly building from a local dev shell."}
   {:label "Focus" :value "Web systems" :detail "Product infrastructure, UI composition, and maintainable tooling."}
   {:label "Stack" :value "Clojure" :detail "Ring, hiccup, Datastar, and plain CSS over a large client build."}])

(def project-cards
  [{:kicker "01"
    :title "Personal Publishing"
    :body "Essays, working notes, and experiments published from a codebase small enough to revise quickly."}
   {:kicker "02"
    :title "Product Prototypes"
    :body "Fast server-rendered prototypes for testing interaction ideas before committing to larger architecture."}
   {:kicker "03"
    :title "Developer Tooling"
    :body "Scripts, environments, and internal interfaces that reduce friction instead of layering abstraction."}])

(def note-items
  [{:title "Writing software that stays legible"
    :body "Keeping the page, the routes, and the state transitions close enough that maintenance feels obvious."}
   {:title "HTML over indirection"
    :body "Choosing server-rendered fragments and direct links unless complexity clearly justifies something heavier."}
   {:title "Design with a point of view"
    :body "Using sharp type, hard borders, and deliberate contrast so the site feels authored instead of templated."}])

(defn read-dotenv []
  (let [path ".env"]
    (when (.exists (java.io.File. path))
      (into {}
            (keep (fn [line]
                    (let [line (str/trim line)]
                      (when (and (not (str/blank? line))
                                 (not (str/starts-with? line "#"))
                                 (str/includes? line "="))
                        (let [[k v] (str/split line #"=" 2)]
                          [(str/trim k) (str/trim v)])))))
            (str/split-lines (slurp path))))))

(defonce !dotenv (delay (read-dotenv)))

(defn env [k]
  (or (System/getenv k)
      (get @!dotenv k)))

(def at-handle
  (env "AT_HANDLE"))

(def bsky-profile-url
  "https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=")

(def bsky-feed-url
  "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed?actor=")

(def bsky-posts-url
  "https://public.api.bsky.app/xrpc/app.bsky.feed.getPosts?uris=")

(def jetstream-url
  "wss://jetstream2.us-west.bsky.network/subscribe")

(defonce !bsky-profile-cache (atom {:handle nil :fetched-at 0 :value nil}))
(defonce !bsky-posts-cache (atom {:handle nil :fetched-at 0 :value nil}))
(defonce !bsky-post-view-cache (atom {}))
(def bsky-cache-ms 300000)
(def bsky-post-view-cache-ms 3600000)

(def live-likes-max-items 8)
(def live-likes-delay-ms 1400)
(def live-likes-poll-ms 250)
(def live-likes-idle-status "Listening for new likes.")
(def live-likes-buffer-status "Buffering new likes so the page stays readable.")

(def stream-time-format
  (DateTimeFormatter/ofPattern "HH:mm:ss")
  )

(defonce !live-likes-state
  (atom {:handle at-handle
         :did nil
         :display-name nil
         :like-counts {}
         :likes []
         :status (if (str/blank? at-handle)
                   "Set AT_HANDLE in .env to start the live likes stream."
                   "Connecting to Jetstream...")
         :version 0}))

(defonce !live-likes-runtime (atom nil))

(def now-controller-script "
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
    source.addEventListener('now-panel', function (event) {
      const payload = JSON.parse(event.data);
      replacePanel(payload.html);
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
")

(def likes-controller-script "
window.yawLikes = (function () {
  let source = null;
  let started = false;

  function currentPanel() {
    return document.getElementById('likes-panel');
  }

  function setStatus(text) {
    const node = document.querySelector('#likes-panel .likes-status');
    if (node) node.textContent = text;
  }

  function replacePanel(html) {
    const next = new DOMParser().parseFromString(html, 'text/html').body.firstElementChild;
    const current = currentPanel();
    if (current && next) current.replaceWith(next);
  }

  function connect() {
    if (source) return;
    setStatus('Connecting to Jetstream...');
    source = new EventSource('/streams/bluesky-likes');
    source.addEventListener('bluesky-likes-panel', function (event) {
      const payload = JSON.parse(event.data);
      replacePanel(payload.html);
    });
    source.onerror = function () {
      if (source) {
        source.close();
        source = null;
      }
      setStatus('Realtime stream interrupted. Retrying...');
      window.setTimeout(connect, 3000);
    };
  }

  function start() {
    if (started || !currentPanel()) return;
    started = true;
    connect();
  }

  return { start };
}());

window.addEventListener('DOMContentLoaded', function () {
  window.yawLikes.start();
});
")

(def styles "
:root {
  --paper: #f3d33b;
  --ink: #121212;
  --edge: #121212;
  --muted: rgba(18, 18, 18, 0.68);
  --panel: rgba(255, 244, 160, 0.82);
  --accent: #121212;
  --page-max: 1500px;
  --page-gutter: clamp(0.9rem, 2vw, 1.6rem);
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
  width: min(var(--page-max), calc(100vw - (var(--page-gutter) * 2)));
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
  grid-template-columns: minmax(0, 1.8fr) minmax(18rem, 0.62fr);
  gap: 1.35rem;
  align-items: start;
}

.hero-card, .aside-card, .feature, .footer-box, .stream-box, .quote-box {
  border: 3px solid var(--edge);
  background: var(--panel);
  box-shadow: 0.45rem 0.45rem 0 0 var(--edge);
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
  gap: 0.75rem;
  margin-top: 1.25rem;
}

.button {
  appearance: none;
  border: 3px solid var(--edge);
  background: var(--ink);
  color: var(--paper);
  padding: 0.78rem 1rem;
  font: 700 0.95rem/1 Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  cursor: pointer;
}

.button.alt {
  background: rgba(255, 244, 160, 0.35);
  color: var(--ink);
}

.aside-card {
  padding: 1rem;
}

.metric {
  display: grid;
  gap: 0.18rem;
  padding: 0.7rem 0;
  border-top: 2px solid rgba(18, 18, 18, 0.2);
}

.metric:first-of-type {
  border-top: 0;
  padding-top: 0;
}

.metric-value {
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  font-size: 1.7rem;
  font-weight: 800;
  line-height: 1;
}

.muted {
  color: var(--muted);
}

.features {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
  gap: 1rem;
  margin-top: 1.5rem;
}

.feature {
  padding: 1rem;
}

.feature h2 {
  margin: 0.2rem 0 0.45rem;
  font-size: 1.6rem;
  line-height: 1;
}

.feature, .footer-box, .stream-box, .aside-card {
  backdrop-filter: blur(0);
}

.stream-section, .notes-grid, .footer-grid, .feed-grid {
  margin-top: 1.6rem;
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) minmax(17rem, 0.55fr);
  gap: 1.2rem;
}

.stream-box, .footer-box {
  padding: 1rem;
}

#now-items {
  display: grid;
  gap: 0.7rem;
  min-height: 12rem;
}

.line {
  border-top: 2px solid rgba(18, 18, 18, 0.2);
  padding-top: 0.7rem;
  font-size: 1.1rem;
}

.line:first-child {
  border-top: 0;
  padding-top: 0;
}

code, .mono {
  font-family: Berkeley Mono, JetBrains Mono, SFMono-Regular, monospace;
  font-size: 0.95em;
}

.notes-list {
  display: grid;
  gap: 0.8rem;
  margin-top: 0.5rem;
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

.bsky-images {
  display: grid;
  gap: 0.6rem;
  margin-top: 0.7rem;
}

.bsky-image {
  display: block;
  width: 100%;
  max-width: 44rem;
  max-height: 34rem;
  border: 2px solid var(--edge);
  object-fit: cover;
  background: rgba(18, 18, 18, 0.08);
}

.likes-list {
  display: grid;
  gap: 0.9rem;
  margin-top: 0.75rem;
}

.like-item {
  border-top: 2px solid rgba(18, 18, 18, 0.18);
  padding-top: 0.75rem;
}

.like-item:first-child {
  border-top: 0;
  padding-top: 0;
}

.like-meta {
  display: flex;
  justify-content: space-between;
  gap: 0.75rem;
  align-items: baseline;
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.like-item p {
  margin: 0.35rem 0 0;
  white-space: pre-wrap;
}

.like-images {
  display: grid;
  gap: 0.6rem;
  margin-top: 0.7rem;
}

.likes-status {
  margin-top: 0.9rem;
}

.note-item {
  border-top: 2px solid rgba(18, 18, 18, 0.18);
  padding-top: 0.75rem;
}

.note-item:first-child {
  border-top: 0;
  padding-top: 0;
}

.note-item h3 {
  margin: 0 0 0.25rem;
  font-size: 1.25rem;
}

.quote-box {
  padding: 1rem;
  margin-top: 1rem;
  background: var(--ink);
  color: var(--paper);
  box-shadow: none;
}

.quote-box blockquote {
  margin: 0;
  font-size: 1.3rem;
  line-height: 1.25;
}

.quote-box p {
  margin: 0.6rem 0 0;
  color: rgba(243, 211, 59, 0.82);
}

.footer-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.notes-grid {
  grid-template-columns: minmax(0, 1.55fr) minmax(17rem, 0.65fr);
}

.feed-grid {
  grid-template-columns: minmax(0, 2.2fr) minmax(15rem, 0.42fr);
}

@media (max-width: 1180px) {
  .hero, .stream-section, .notes-grid, .feed-grid {
    grid-template-columns: 1fr;
  }
}

.section-title {
  margin: 0 0 0.35rem;
  font-size: 2rem;
  line-height: 0.98;
}

.section-copy {
  margin: 0;
  max-width: 44rem;
}

#now-panel .now-status {
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

@media (max-width: 840px) {
  .hero, .stream-section, .features, .footer-grid, .notes-grid, .feed-grid {
    grid-template-columns: 1fr;
  }

  .page {
    width: min(100vw - 1rem, 1080px);
  }

  h1 {
    font-size: clamp(2.7rem, 18vw, 4.9rem);
  }
}
")

(defn nav-link [active href label]
  [:a {:href href
       :aria-current (when (= active label) "page")}
   label])

(defn hero-section []
  [:section.hero
   [:article.hero-card
    [:div.eyebrow "Personal Website"]
    [:h1 "Building quiet software with a strong point of view."]
    [:p.lede
     "I work on web products, developer systems, and interfaces that stay legible under real maintenance. This site is a compact home for projects, notes, and current work."]
    [:div.cta-row
     [:a.button {:href "/contact"} "Get in touch"]
     [:button.button.alt
      {:type "button"
       :onclick "window.yawNow.refresh()"}
      "Refresh now"]]
    [:div.quote-box
     [:div.eyebrow "Working Principle"]
     [:blockquote "Fewer moving parts, stronger defaults, sharper writing."]
     [:p.muted "The site stays intentionally small so changing it never feels expensive."]]]
   [:aside.aside-card
    [:div.eyebrow "At a glance"]
    (for [{:keys [label value detail]} profile-metrics]
      [:div.metric {:key label}
       [:div.metric-label label]
       [:div.metric-value value]
       [:div.muted detail]])]])

(defn work-section []
  [:section.features
   (for [{:keys [kicker title body]} project-cards]
     [:article.feature {:key title}
      [:div.kicker kicker]
      [:h2 title]
      [:p body]])])

(defn now-section []
  [:section.stream-section
   [:article.stream-box
    [:div.eyebrow "Now"]
    [:div#now-panel
     [:div#now-items
      [:div.line "Shipping software with low ceremony and strong editorial structure."]
      [:div.line "Documenting ideas in public as working notes instead of polished launch copy."]]
     [:p.muted.now-status "Press “Refresh now” to pull a live update from the server."]]]
   [:div.footer-box
    [:div.eyebrow "Approach"]
    [:h2.section-title "Built to be edited, not admired from a distance."]
    [:p.section-copy
     "I prefer systems where content, behavior, and deployment stay close together. That usually means server-rendered pages, direct interfaces, and a bias toward tools that are easy to inspect."]]])

(defn notes-section []
  [:section.notes-grid
   [:article.footer-box
    [:div.eyebrow "Notes"]
    [:h2.section-title "Ongoing themes"]
    [:div.notes-list
     (for [{:keys [title body]} note-items]
       [:article.note-item {:key title}
        [:h3 title]
        [:p body]])]]
   [:div.footer-box
    [:div.eyebrow "Source"]
    [:p "This site runs as a single Clojure service with server-rendered HTML and a small Datastar hook for lightweight updates."]
    [:p.mono "devenv shell -- clojure -M -m yaw.core"]]])

(defn contact-section []
  [:section.notes-grid
   [:aside.footer-box
    [:div.eyebrow "Contact"]
    [:h2.section-title "Available for careful product and engineering work."]
    [:p "If you need help shaping a web product, simplifying a delivery path, or tightening the feel of an interface, reach out."]
    [:p.mono "nandi@localhost"]
    [:p.mono "github.com/nandi"]
    [:p.muted "Replace these placeholders with your real contact points."]]
   [:div.footer-box
    [:div.eyebrow "Intent"]
   [:p "The goal is a personal website that feels authored: compact, opinionated, and easy to keep current over time."]
    [:p "No feed clutter, no generic portfolio chrome, no split between content and implementation."]]])

(defn http-client []
  (-> (HttpClient/newBuilder)
      (.build)))

(defn url-encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn send-json-get [client url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode response))
      (json/parse-string (.body response) true))))

(defn update-version [state]
  (update state :version (fnil inc 0)))

(defn set-live-likes-status! [status]
  (swap! !live-likes-state
         (fn [state]
           (-> state
               (assoc :status status)
               update-version))))

(defn remember-like [state item]
  (let [post-uri (:post-uri item)
        like-counts (update (:like-counts state) post-uri (fnil inc 0))
        like-count (get like-counts post-uri 0)
        item (assoc item :like-count like-count)
        likes (if (>= like-count 2)
                (->> (cons item (remove #(= (:post-uri %) post-uri) (:likes state)))
                     (take live-likes-max-items)
                     vec)
                (:likes state))]
    (assoc state
           :like-counts like-counts
           :likes likes)))

(defn format-stream-time [value]
  (when value
    (-> (Instant/ofEpochMilli (quot (long value) 1000))
        (.atZone (ZoneId/systemDefault))
        (.format stream-time-format))))

(defn format-iso-time [value]
  (when value
    (try
      (-> (Instant/parse value)
          (.atZone (ZoneId/systemDefault))
          (.format stream-time-format))
      (catch Exception _
        value))))

(defn fetch-bsky-profile []
  (cond
    (str/blank? at-handle)
    {:handle nil
     :display-name nil
     :did nil
     :error "Set AT_HANDLE in .env to show Bluesky data."}

    :else
    (let [{:keys [handle fetched-at value]} @!bsky-profile-cache
          now (System/currentTimeMillis)]
      (if (and (= handle at-handle)
               value
               (< (- now fetched-at) bsky-cache-ms))
        value
        (let [client (http-client)]
          (try
            (let [profile (send-json-get client (str bsky-profile-url (url-encode at-handle)))
                  value {:handle (or (:handle profile) at-handle)
                         :display-name (:displayName profile)
                         :did (:did profile)
                         :error nil}]
              (swap! !bsky-profile-cache assoc :handle at-handle :fetched-at now :value value)
              value)
            (catch Exception _
              {:handle at-handle
               :display-name nil
               :did nil
               :error "Unable to load the Bluesky profile right now."})))))))

(defn bsky-post-url [handle uri]
  (when (and handle uri)
    (let [[_ _ _ collection rkey] (str/split uri #"/" 5)]
      (when (and (= "app.bsky.feed.post" collection) rkey)
        (str "https://bsky.app/profile/" handle "/post/" rkey)))))

(defn trim-post-text [text]
  (let [text (or text "")]
    (if (> (count text) 280)
      (str (subs text 0 277) "...")
      text)))

(defn image-view-items [post-view]
  (let [embed (:embed post-view)]
    (cond
      (seq (:images embed))
      (:images embed)

      (seq (get-in embed [:media :images]))
      (get-in embed [:media :images])

      :else nil)))

(defn post-images [post-view]
  (->> (image-view-items post-view)
       (keep (fn [image]
               (let [src (or (:fullsize image)
                             (:thumb image)
                             (get-in image [:image :fullsize])
                             (get-in image [:image :thumb]))]
                 (when src
                   {:src src
                    :alt (or (:alt image) "Bluesky post image")}))))
       vec))

(defn fetch-bsky-post-view [uri]
  (let [cached (get @!bsky-post-view-cache uri)
        now (System/currentTimeMillis)]
    (if (and cached
             (< (- now (:fetched-at cached)) bsky-post-view-cache-ms))
      (:value cached)
      (let [client (http-client)]
        (try
          (let [response (send-json-get client (str bsky-posts-url (url-encode uri)))
                view (first (:posts response))]
            (swap! !bsky-post-view-cache assoc uri {:fetched-at now :value view})
            view)
          (catch Exception _
            nil))))))

(defn fetch-bsky-posts []
  (cond
    (str/blank? at-handle)
    {:handle nil :posts [] :error "Set AT_HANDLE in .env to show recent Bluesky posts."}

    :else
    (let [{:keys [handle fetched-at value]} @!bsky-posts-cache
          now (System/currentTimeMillis)]
      (if (and (= handle at-handle)
               value
               (< (- now fetched-at) bsky-cache-ms))
        value
        (let [client (http-client)]
          (try
            (let [profile (fetch-bsky-profile)
                  feed (send-json-get client (str bsky-feed-url (url-encode at-handle) "&limit=4"))
                  posts (->> (:feed feed)
                             (keep (fn [item]
                                     (let [view (:post item)
                                           author (:author view)
                                           record (:record view)
                                           text (or (:text record) (:text (:value record)))]
                                       (when (and view text)
                                         {:uri (:uri view)
                                          :text (trim-post-text text)
                                          :indexed-at (or (:indexedAt view) (:indexedAt record))
                                          :handle (or (:handle author) at-handle)
                                          :display-name (:displayName author)
                                          :images (post-images view)}))))
                             vec)
                  value {:handle (or (:handle profile) at-handle)
                         :display-name (:display-name profile)
                         :posts posts
                         :error nil}]
              (swap! !bsky-posts-cache assoc :handle at-handle :fetched-at now :value value)
              value)
            (catch Exception _
              {:handle at-handle
               :posts []
               :error "Unable to load Bluesky posts right now."})))))))

(defn like-event->item [event]
  (let [subject-uri (get-in event [:commit :record :subject :uri])
        post-view (when subject-uri (fetch-bsky-post-view subject-uri))
        author (:author post-view)
        record (:record post-view)
        text (or (:text record)
                 (:text (:value record))
                 "Liked a post without a text preview.")
        post-uri (or (:uri post-view) subject-uri)]
    {:id (str (:did event) ":" (get-in event [:commit :rkey]))
     :text (trim-post-text text)
     :author-handle (:handle author)
     :author-name (:displayName author)
     :liked-at (or (format-iso-time (get-in event [:commit :record :createdAt]))
                   (format-stream-time (:time_us event))
                   "recent")
     :post-url (bsky-post-url (:handle author) post-uri)
     :post-uri post-uri
     :images (post-images post-view)}))

(defn push-live-like! [{:keys [handle did display-name]} event pending]
  (let [item (like-event->item event)
        status (if (pos? pending)
                 (str live-likes-buffer-status " " pending " more queued.")
                 live-likes-idle-status)]
    (swap! !live-likes-state
           (fn [state]
             (-> state
                 (assoc :handle handle
                        :did did
                        :display-name display-name
                        :status status)
                 (remember-like item)
                 update-version)))))

(defn like-event? [event]
  (and (= "commit" (:kind event))
       (= "create" (get-in event [:commit :operation]))
       (= "app.bsky.feed.like" (get-in event [:commit :collection]))))

(defn live-likes-panel [{:keys [likes status]}]
  [:div#likes-panel
   (if (seq likes)
     [:div.likes-list
      (for [{:keys [id text author-handle author-name liked-at post-url post-uri like-count images]} likes]
        [:article.like-item {:key (or post-uri id)}
         [:div.like-meta
          [:strong (or author-name author-handle "Bluesky post")]
          [:span.muted (str like-count " likes / " liked-at)]]
         [:p text]
         (when (seq images)
           [:div.like-images
            (for [{:keys [src alt]} images]
              [:img.bsky-image {:key src
                                :src src
                                :alt alt
                                :loading "lazy"}])])
         [:p
          (if post-url
            [:a.mono {:href post-url :target "_blank" :rel "noreferrer"}
             "Open liked post"]
            [:span.mono (or post-uri "Post unavailable")])]])]
     [:p.muted "Waiting for a post to reach 2 likes on Jetstream."])
   [:p.muted.likes-status status]])

(defn live-likes-fragment []
  (str
   (h/html
    (live-likes-panel @!live-likes-state))))

(defn send-live-likes-panel! [sse]
  (dsse/send-event! sse
                    "bluesky-likes-panel"
                    [(json/generate-string {:html (live-likes-fragment)})]))

(defn live-likes-running? []
  (let [{:keys [stream-future drain-future]} @!live-likes-runtime]
    (and stream-future
         drain-future
         (not (future-done? stream-future))
         (not (future-done? drain-future)))))

(defn drain-live-likes! [running? queue]
  (while @running?
    (when-let [{:keys [profile event]} (.poll queue 1 TimeUnit/SECONDS)]
      (push-live-like! profile event (.size queue))
      (when @running?
        (Thread/sleep live-likes-delay-ms)))))

(defn connect-jetstream! [client queue websocket*]
  (let [uri (str jetstream-url
                 "?wantedCollections=app.bsky.feed.like")
        close-signal (promise)
        buffer (StringBuilder.)]
    (-> (.newWebSocketBuilder client)
        (.buildAsync
         (URI/create uri)
         (reify WebSocket$Listener
           (onOpen [_ websocket]
             (reset! websocket* websocket)
             (.request websocket 1)
             (set-live-likes-status! live-likes-idle-status))

           (onText [_ websocket data last]
             (.append buffer data)
             (when last
               (let [message (str buffer)]
                 (.setLength buffer 0)
                 (try
                   (let [event (json/parse-string message true)]
                     (when (like-event? event)
                       (.offer queue {:event event})
                       (set-live-likes-status! live-likes-buffer-status)))
                   (catch Exception _
                     nil))))
             (.request websocket 1)
             (CompletableFuture/completedFuture nil))

           (onClose [_ _ status-code reason]
             (deliver close-signal {:status status-code :reason reason})
             (CompletableFuture/completedFuture nil))

           (onError [_ _ error]
             (deliver close-signal {:status :error :reason (.getMessage error)}))))
        .join)
    close-signal))

(defn stream-live-likes! [running? websocket* queue]
  (let [client (http-client)]
    (while @running?
      (try
        (let [close-signal (connect-jetstream! client queue websocket*)
              {:keys [reason]} @close-signal]
          (when @running?
            (set-live-likes-status! (str (or reason "Jetstream disconnected.") " Reconnecting..."))
            (Thread/sleep 3000)))
        (catch Exception _
          (when @running?
            (set-live-likes-status! "Unable to connect to Jetstream. Retrying...")
            (Thread/sleep 3000)))))))

(defn ensure-live-likes! []
  (cond
    (live-likes-running?)
    @!live-likes-runtime

    :else
    (let [running? (atom true)
          websocket* (atom nil)
          queue (LinkedBlockingDeque.)
          drain-future (future (drain-live-likes! running? queue))
          stream-future (future (stream-live-likes! running? websocket* queue))
          runtime {:running? running?
                   :websocket websocket*
                   :queue queue
                   :drain-future drain-future
                   :stream-future stream-future}]
      (reset! !live-likes-runtime runtime)
      runtime)))

(defn stop-live-likes! []
  (when-let [{:keys [running? websocket drain-future stream-future]} @!live-likes-runtime]
    (reset! running? false)
    (when-let [socket @websocket]
      (.abort socket))
    (future-cancel drain-future)
    (future-cancel stream-future)
    (reset! !live-likes-runtime nil)))

(defn bluesky-section []
  (let [{:keys [handle display-name posts error]} (fetch-bsky-posts)]
    [:section.feed-grid
     [:article.footer-box
      [:div.eyebrow "Bluesky"]
      [:h2.section-title "Recent posts"]
      (cond
        error [:p.muted error]
        (seq posts)
        [:div.bsky-list
         (for [{:keys [uri text indexed-at handle images]} posts]
           [:article.bsky-post {:key uri}
            [:div.bsky-meta
             [:strong (or display-name handle)]
             [:time.muted {:datetime indexed-at
                          :data-local-timestamp ""}
              (or indexed-at "recent")]]
            [:p text]
            (when (seq images)
              [:div.bsky-images
               (for [{:keys [src alt]} images]
                 [:img.bsky-image {:key src
                                   :src src
                                   :alt alt
                                   :loading "lazy"}])])
            (when-let [url (bsky-post-url handle uri)]
              [:p
               [:a.mono {:href url :target "_blank" :rel "noreferrer"}
                "Open on Bluesky"]])])]
        :else [:p.muted "No recent posts found."])]
     [:aside.footer-box
      [:div.eyebrow "Handle"]
      [:p.mono (or handle "AT_HANDLE not set")]
      [:p "Posts are fetched server-side from the public Bluesky API and cached briefly to keep page loads predictable."]]]))

(defn live-likes-section []
  (ensure-live-likes!)
  [:section.feed-grid
   [:article.footer-box
    [:div.eyebrow "Jetstream"]
    [:h2.section-title "All live likes"]
    (live-likes-panel @!live-likes-state)]
   [:aside.footer-box
    [:div.eyebrow "Stream"]
    [:p.mono "app.bsky.feed.like"]
    [:p "All like events stream in from Jetstream and are intentionally paced before rendering so bursts stay readable."]]])

(defn layout [active sections]
  (str
   "<!doctype html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "Nandi"]
      [:style styles]
      [:script (h/raw now-controller-script)]
      [:script (h/raw likes-controller-script)]
      [:script {:type "module"
                :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.2/bundles/datastar.js"}]]
     [:body
      (into
       [:main.page
        [:header.masthead
         [:div "Nandi"]
         [:nav
          (nav-link active "/" "Home")
          (nav-link active "/work" "Work")
          (nav-link active "/notes" "Notes")
          (nav-link active "/contact" "Contact")]]]
       sections)]])))

(defn home [_]
  (-> (layout "Home" [(bluesky-section) (live-likes-section) (hero-section) (work-section) (now-section) (notes-section) (contact-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn work-page [_]
  (-> (layout "Work" [(hero-section) (work-section) (now-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn notes-page [_]
  (-> (layout "Notes" [(bluesky-section) (live-likes-section) (hero-section) (notes-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn contact-page [_]
  (-> (layout "Contact" [(hero-section) (contact-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn line-fragment [idx text]
  (str
   (h/html
    [:div.line {:id (str "line-" idx)}
     [:span.mono (format "%02d" (inc idx))]
     " "
     text])))

(defn now-fragment [lines status]
  (str
   (h/html
    [:div#now-panel
     [:div#now-items
      (for [[idx text] (map-indexed vector lines)]
        [:div.line {:id (str "line-" idx) :key (str "line-" idx)}
         [:span.mono (format "%02d" (inc idx))]
         " "
         text])]
     [:p.muted.now-status status]])))

(defn send-now-panel! [sse lines status]
  (dsse/send-event! sse
                    "now-panel"
                    [(json/generate-string {:html (now-fragment lines status)})]))

(defn local-timestamp [instant]
  [:time {:datetime (str instant)
          :data-local-timestamp ""}
   (str instant)])

(defn live-likes-stream [request]
  (ensure-live-likes!)
  (->sse-response
   request
   (hash-map
    on-open
    (fn [sse]
      (d*/with-open-sse sse
        (loop [seen-version nil ticks 0]
          (let [{:keys [version]} @!live-likes-state]
            (when (not= version seen-version)
              (send-live-likes-panel! sse))
            (Thread/sleep live-likes-poll-ms)
            (when (zero? (mod ticks 40))
              (dsse/send-event! sse "likes-heartbeat" ["{}"]))
            (recur version (inc ticks)))))))))

(defn now-stream [request]
  (->sse-response
   request
   (hash-map
    on-open
    (fn [sse]
      (d*/with-open-sse sse
        (let [server-time (Instant/now)]
          (send-now-panel! sse [(list "Updating from the server at "
                                  (local-timestamp server-time)
                                  ".")]
                           "Receiving fresh lines..."))
        (doseq [idx (range (count now-lines))]
          (Thread/sleep 450)
          (send-now-panel! sse (take (inc idx) now-lines) "Receiving fresh lines..."))
        (send-now-panel! sse now-lines "Updated just now."))))))

(defn favicon [_]
  {:status 204
   :headers {"content-type" "image/x-icon"}
   :body ""})

(def routes
  [["/" {:get home}]
   ["/work" {:get work-page}]
   ["/notes" {:get notes-page}]
   ["/contact" {:get contact-page}]
   ["/favicon.ico" {:get favicon}]
   ["/streams/bluesky-likes" {:get live-likes-stream}]
   ["/streams/now" {:get now-stream}]])

(def app
  (ring/ring-handler
   (ring/router routes)))

(defonce !server (atom nil))

(defn stop! []
  (stop-live-likes!)
  (when-let [server @!server]
    (http-kit/server-stop! server)
    (reset! !server nil)))

(defn start! [{:keys [port] :or {port 8080}}]
  (stop!)
  (reset! !server
          (http-kit/run-server app {:port port
                                    :legacy-return-value? false}))
  port)

(defn -main [& _]
  (let [port (start! {:port 8080})]
    (println (str "Yaw running on http://localhost:" port))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(do (stop!) (shutdown-agents))))))
