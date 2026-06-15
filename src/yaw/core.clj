(ns yaw.core
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.time LocalTime))
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

(defonce !bsky-cache (atom {:handle nil :fetched-at 0 :value nil}))
(def bsky-cache-ms 300000)

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

  function replacePanel(html) {
    const next = new DOMParser().parseFromString(html, 'text/html').body.firstElementChild;
    const current = currentPanel();
    if (current && next) current.replaceWith(next);
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

  return { refresh };
}());
")

(def styles "
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
  width: min(1120px, calc(100vw - 2rem));
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
  grid-template-columns: minmax(0, 1.35fr) minmax(19rem, 0.85fr);
  gap: 1.2rem;
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
  grid-template-columns: repeat(3, minmax(0, 1fr));
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

.stream-section, .notes-grid, .footer-grid {
  margin-top: 1.6rem;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(18rem, 0.85fr);
  gap: 1rem;
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

.bsky-featured {
  display: grid;
  gap: 0.65rem;
  padding: 1rem;
  margin-top: 0.8rem;
  border: 3px solid var(--edge);
  background: rgba(255, 244, 160, 0.54);
}

.bsky-featured .bsky-meta {
  border-bottom: 2px solid rgba(18, 18, 18, 0.18);
  padding-bottom: 0.55rem;
}

.bsky-featured p {
  margin: 0;
  font-size: 1.15rem;
  line-height: 1.4;
  white-space: pre-wrap;
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
  grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.9fr);
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
  .hero, .stream-section, .features, .footer-grid, .notes-grid {
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

(defn send-json-get [client url]
  (let [request (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode response))
      (json/parse-string (.body response) true))))

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

(defn fetch-bsky-posts []
  (cond
    (str/blank? at-handle)
    {:handle nil :posts [] :error "Set AT_HANDLE in .env to show recent Bluesky posts."}

    :else
    (let [{:keys [handle fetched-at value]} @!bsky-cache
          now (System/currentTimeMillis)]
      (if (and (= handle at-handle)
               value
               (< (- now fetched-at) bsky-cache-ms))
        value
        (let [client (http-client)]
          (try
            (let [profile (send-json-get client (str bsky-profile-url at-handle))
                  feed (send-json-get client (str bsky-feed-url at-handle "&limit=4"))
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
                                          :display-name (:displayName author)}))))
                             vec)
                  value {:handle (or (:handle profile) at-handle)
                         :display-name (:displayName profile)
                         :posts posts
                         :error nil}]
              (swap! !bsky-cache assoc :handle at-handle :fetched-at now :value value)
              value)
            (catch Exception _
              {:handle at-handle
               :posts []
               :error "Unable to load Bluesky posts right now."})))))))

(defn bluesky-section []
  (let [{:keys [handle display-name posts error]} (fetch-bsky-posts)]
    [:section.notes-grid
     [:article.footer-box
      [:div.eyebrow "Bluesky"]
      [:h2.section-title "Recent posts"]
      (cond
        error [:p.muted error]
        (seq posts)
        (let [[featured-post & recent-posts] posts]
          [:<>
           (when featured-post
             (let [{:keys [uri text indexed-at handle]} featured-post]
               [:article.bsky-featured {:key uri}
                [:div.eyebrow "Featured post"]
                [:div.bsky-meta
                 [:strong (or display-name handle)]
                 [:span.muted (or indexed-at "recent")]]
                [:p text]
                (when-let [url (bsky-post-url handle uri)]
                  [:p
                   [:a.mono {:href url :target "_blank" :rel "noreferrer"}
                    "Open on Bluesky"]])]))
           (when (seq recent-posts)
             [:div.bsky-list
              (for [{:keys [uri text indexed-at handle]} recent-posts]
                [:article.bsky-post {:key uri}
                 [:div.bsky-meta
                  [:strong (or display-name handle)]
                  [:span.muted (or indexed-at "recent")]]
                 [:p text]
                 (when-let [url (bsky-post-url handle uri)]
                   [:p
                    [:a.mono {:href url :target "_blank" :rel "noreferrer"}
                     "Open on Bluesky"]])])])])
        :else [:p.muted "No recent posts found."])]
     [:aside.footer-box
      [:div.eyebrow "Handle"]
      [:p.mono (or handle "AT_HANDLE not set")]
      [:p "Posts are fetched server-side from the public Bluesky API and cached briefly to keep page loads predictable."]]]))

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
  (-> (layout "Home" [(hero-section) (work-section) (now-section) (bluesky-section) (notes-section) (contact-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn work-page [_]
  (-> (layout "Work" [(hero-section) (work-section) (now-section)])
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn notes-page [_]
  (-> (layout "Notes" [(hero-section) (bluesky-section) (notes-section)])
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

(defn now-stream [request]
  (->sse-response
   request
   (hash-map
    on-open
    (fn [sse]
      (d*/with-open-sse sse
        (send-now-panel! sse [(str "Updating from the server at " (LocalTime/now) ".")] "Receiving fresh lines...")
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
   ["/streams/now" {:get now-stream}]])

(def app
  (ring/ring-handler
   (ring/router routes)))

(defonce !server (atom nil))

(defn stop! []
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
