(ns yaw.core
  (:require
   [hiccup2.core :as h]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as ring]
   [ring.util.response :as response]
   [starfederation.datastar.clojure.adapter.http-kit :refer [->sse-response on-open]]
   [starfederation.datastar.clojure.api :as d*]))

(def manifesto-lines
  ["Backend-driven UI without SPA ceremony."
   "Datastar patches the DOM, the server owns the story."
   "Clojure keeps the state and the shape of the page close together."
   "The palette stays blunt: yellow paper, black ink, no decorative fog."])

(def feature-cards
  [{:kicker "STACK"
    :title "Datastar over SSE"
    :body "Interactions stream as HTML fragments from Clojure instead of hydrating a client framework."}
   {:kicker "TOOLING"
    :title "devenv first"
    :body "The shell provides JDK 21, Clojure CLI, a REPL entrypoint, and a single dev command."}
   {:kicker "AESTHETIC"
    :title "Black on yellow"
    :body "High-contrast blocks, hard borders, tight spacing, and editorial typography borrowed from tonsky.me's visual attitude."}])

(def styles
  "
:root {
  --paper: #f3d33b;
  --ink: #121212;
  --edge: #121212;
  --muted: rgba(18, 18, 18, 0.68);
  --panel: rgba(255, 245, 177, 0.72);
}

* { box-sizing: border-box; }

html {
  background: var(--paper);
  color: var(--ink);
  font-family: Iowan Old Style, Palatino Linotype, Book Antiqua, URW Palladio L, serif;
  line-height: 1.35;
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
  width: min(1080px, calc(100vw - 2rem));
  margin: 0 auto;
  padding: 1.2rem 0 3rem;
}

.masthead {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  align-items: baseline;
  border-bottom: 3px solid var(--edge);
  padding-bottom: 0.75rem;
  margin-bottom: 1.4rem;
  font-family: Avenir Next Condensed, Franklin Gothic Medium, Arial Narrow, sans-serif;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  font-size: 0.9rem;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(18rem, 0.9fr);
  gap: 1.2rem;
  align-items: start;
}

.hero-card, .aside-card, .feature, .footer-box, .stream-box {
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
}

h1 {
  margin: 0.35rem 0 0.8rem;
  font-size: clamp(2.8rem, 9vw, 6.8rem);
  line-height: 0.92;
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
  background: transparent;
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
  font-size: 2rem;
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

.stream-section {
  margin-top: 1.6rem;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(18rem, 0.85fr);
  gap: 1rem;
}

.stream-box, .footer-box {
  padding: 1rem;
}

#manifesto {
  display: grid;
  gap: 0.7rem;
  min-height: 15rem;
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

.footer-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1rem;
  margin-top: 1rem;
}

code, .mono {
  font-family: Berkeley Mono, JetBrains Mono, SFMono-Regular, monospace;
  font-size: 0.95em;
}

@media (max-width: 840px) {
  .hero, .stream-section, .features, .footer-grid {
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

(defn layout []
  (str
   "<!doctype html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title "Yaw"]
      [:style styles]
      [:script {:type "module"
                :src "https://cdn.jsdelivr.net/gh/starfederation/[email protected]/bundles/datastar.js"}]]
     [:body
      [:main.page
       [:header.masthead
        [:div "Yaw / Datastar / Clojure"]
        [:div.mono "black-on-yellow edition"]]
       [:section.hero
        [:article.hero-card
         [:div.eyebrow "No SPA. No Build Graph."]
         [:h1 "Clojure, cut hard."]
         [:p.lede
          "A Datastar site with a deliberately stark editorial surface: black ink on yellow stock, server-rendered HTML, and SSE-driven updates from a tiny Clojure backend."]
         [:div.cta-row
          [:button.button
           {:type "button"
            :data-on:click "@get('/streams/manifesto')"}
           "Stream manifesto"]
          [:button.button.alt
           {:type "button"
            :data-on:click "@get('/fragments/stack')"}
           "Explain stack"]]]
        [:aside.aside-card
         [:div.eyebrow "Readout"]
         [:div.metric
          [:div.metric-label "Runtime"]
          [:div.metric-value "http-kit"]
          [:div.muted "Ring handler plus Datastar SSE adapter"]]
         [:div.metric
          [:div.metric-label "Frontend"]
          [:div.metric-value "11.76 KiB"]
          [:div.muted "Datastar describes itself as a single lightweight file"]]
         [:div.metric
          [:div.metric-label "Mood"]
          [:div.metric-value "Yellow / Black"]
          [:div.muted "A nod to tonsky.me without cloning its layout"]]]]
       [:section.features
        (for [{:keys [kicker title body]} feature-cards]
          [:article.feature {:key title}
           [:div.kicker kicker]
           [:h2 title]
           [:p body]])]
       [:section.stream-section
        [:article.stream-box
         [:div.eyebrow "Server Stream"]
         [:div#manifesto
          [:div.line "Press “Stream manifesto” to let the backend patch lines into this column one by one."]]]
        [:aside.footer-box {:id "stack-note"}
         [:div.eyebrow "Stack Note"]
         [:p
          "Press “Explain stack” for a plain HTML patch response. Datastar will morph it in-place by element id."]
         [:p.mono "devenv shell -- clojure -M -m yaw.core"]]]
       [:section.footer-grid
        [:div.footer-box
         [:div.eyebrow "Why this shape"]
         [:p "The page is intentionally strict: oversized headline, dense panels, hard borders, and almost no decorative color beyond the paper tone."]
         [:p "That keeps the site aligned with the reference aesthetic while still being its own composition."]]
        [:div.footer-box
         [:div.eyebrow "Where Datastar fits"]
         [:p "Buttons are declarative. The backend returns either HTML fragments or an event stream, and Datastar handles the DOM patching."]
         [:p "No client-side router, no hydration boundary, no framework boot step."]]]]]])))

(defn home [_]
  (-> (layout)
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn stack-fragment [_]
  (-> (str
       (h/html
        [:aside.footer-box {:id "stack-note"}
         [:div.eyebrow "Stack Note"]
         [:p "The app uses `http-kit` for the web server, `reitit` for routes, `hiccup` for markup, and the official Datastar Clojure adapter for SSE responses."]
         [:p "The dependency is pinned to the `datastar-clojure` repository commit behind `v1.0.0-RC8`, with `:deps/root` set to `libraries/sdk-http-kit`."]
         [:p.mono "Git ref: aed8ce2"]]))
      response/response
      (response/content-type "text/html; charset=utf-8")))

(defn line-fragment [idx text]
  (str
   (h/html
    [:div.line {:id (str "line-" idx)}
     [:span.mono (format "%02d" (inc idx))]
     " "
     text])))

(defn manifesto-stream [request]
  (->sse-response
   request
   {on-open
    (fn [sse]
      (d*/with-open-sse sse
        (d*/patch-elements!
         sse
         (str
          (h/html
           [:div#manifesto
            [:div.line "Transmission opened. The server is sending fragments..."]])))
        (doseq [[idx line] (map-indexed vector manifesto-lines)]
          (Thread/sleep 450)
          (d*/patch-elements! sse (line-fragment idx line)))))}))

(def routes
  [["/" {:get home}]
   ["/fragments/stack" {:get stack-fragment}]
   ["/streams/manifesto" {:get manifesto-stream}]])

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
