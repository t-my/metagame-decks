#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def base-url "https://www.mtggoldfish.com")
(def max-decks 25)

(def formats
  ["standard"
   "modern"
   "pioneer"
   "historic"
   "explorer"
   "timeless"
   "alchemy"
   "pauper"
   "legacy"
   "vintage"
   "penny_dreadful"
   "premodern"
   "duel_commander"
   "commander"
   "brawl"])

(def max-retries 5)

(defn- retry-after-ms
  "Parse a Retry-After header (delta-seconds) into millis, defaulting to backoff."
  [resp default-ms]
  (if-let [hdr (get-in resp [:headers "retry-after"])]
    (try (* 1000 (Long/parseLong (str/trim hdr)))
         (catch Exception _ default-ms))
    default-ms))

(defn fetch-page [url]
  (loop [attempt 0]
    (let [resp (http/get url {:headers {"User-Agent" "Mozilla/5.0"}
                              :throw   false})]
      (cond
        (= 200 (:status resp))
        (:body resp)

        (and (#{429 500 502 503 504} (:status resp)) (< attempt max-retries))
        (let [backoff (* 1000 (long (Math/pow 2 attempt)))   ; 1s,2s,4s,8s,16s
              wait    (retry-after-ms resp backoff)]
          (println (str "    !! HTTP " (:status resp) " from " url
                        " — retrying in " (long (/ wait 1000)) "s (attempt "
                        (inc attempt) "/" max-retries ")"))
          (Thread/sleep wait)
          (recur (inc attempt)))

        :else
        (throw (ex-info (str "HTTP " (:status resp) " fetching " url)
                        {:status (:status resp) :url url}))))))

;; --- MTG Goldfish scraping ---

(defn extract-archetype-slugs [fmt html]
  (->> (re-seq (re-pattern (str "href=\"(/archetype/" fmt "-[^\"#]+)")) html)
       (map second)
       distinct))

(defn extract-deck-download-id [html]
  (some->> (re-find #"href=\"/deck/download/(\d+)\"" html)
           second))

(defn slug->name [fmt slug]
  (-> slug
      (str/replace (re-pattern (str "^/archetype/" fmt "-")) "")
      (str/replace #"-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" "")))

;; MTG Goldfish's deck download is already the community-standard MTGO plain-text format —
;; "<qty> <card name>" per line, a blank line before the sideboard. We store it verbatim
;; (only normalizing line endings). No XMage `.dck` conversion, no Scryfall printing lookups:
;; MTGO/MTGA text is the interoperable standard, and the consumer resolves cards by name.
(defn normalize-txt [s]
  (str (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n") str/trim) "\n"))

(defn download-deck! [fmt idx slug]
  (let [name    (slug->name fmt slug)
        prefix  (format "%02d" idx)
        url     (str base-url slug "#paper")
        _       (println (str "  [" prefix "] " name))
        html    (fetch-page url)
        deck-id (extract-deck-download-id html)]
    (if deck-id
      (let [deck-txt (fetch-page (str base-url "/deck/download/" deck-id))
            path     (str "decks/" fmt "/" prefix "-" name ".txt")]
        (spit path (normalize-txt deck-txt))
        (println (str "    -> " path)))
      (println (str "    !! no download link found for " name)))))

(defn clear-dir! [dir]
  (let [d (java.io.File. dir)]
    (when (.isDirectory d)
      (doseq [f (.listFiles d)] (.delete f)))
    (.mkdirs d)))

(defn download-format! [fmt]
  (let [dir (str "decks/" fmt)]
    (clear-dir! dir) ; drop archetypes that fell out of the metagame this week
    (println (str "\n=== " (str/upper-case fmt) " ==="))
    (let [url   (str base-url "/metagame/" fmt "/full#paper")
          html  (fetch-page url)
          slugs (take max-decks (extract-archetype-slugs fmt html))]
      (println (str "Found " (count slugs) " archetypes (capped at " max-decks ")"))
      (doseq [[idx slug] (map-indexed #(vector (inc %1) %2) slugs)]
        (download-deck! fmt idx slug)
        (Thread/sleep 250)))))

;; --- manifest ---

;; Human label from a slug/id: "duel_commander" -> "Duel Commander", "dimir-tempo" -> "Dimir Tempo".
(defn title-case [s]
  (->> (str/split s #"[-_ ]+")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn deck-entries [dir]
  (->> (.listFiles (java.io.File. dir))
       (filter #(str/ends-with? (.getName %) ".txt"))
       (sort-by #(.getName %))
       (mapv (fn [f]
               (let [fname     (.getName f)
                     archetype (-> fname (str/replace #"\.txt$" "") (str/replace #"^\d+-" ""))]
                 {:file fname :name (title-case archetype)})))))

;; Scan decks/ and write manifest.json — the index the gateway (or any client) reads to list
;; formats and their decks without walking the tree itself.
(defn build-manifest! []
  (let [root (java.io.File. "decks")
        fmts (->> (when (.isDirectory root) (.listFiles root))
                  (filter #(.isDirectory %))
                  (sort-by #(.getName %))
                  (keep (fn [d]
                          (let [decks (deck-entries (.getPath d))]
                            (when (seq decks)
                              {:id (.getName d) :name (title-case (.getName d)) :decks decks}))))
                  vec)
        manifest {:updated (str (java.time.LocalDate/now)) :formats fmts}]
    (spit "manifest.json" (str (json/generate-string manifest {:pretty true}) "\n"))
    (println (str "\nWrote manifest.json — " (count fmts) " formats, "
                  (reduce + (map #(count (:decks %)) fmts)) " decks"))))

(defn -main [& args]
  (let [cmd (first args)]
    (cond
      (nil? cmd)
      (do (println "Usage: bb download.clj <format>|all|manifest")
          (println (str "Formats: " (str/join ", " formats)))
          (System/exit 1))

      (= cmd "manifest")           ; regenerate manifest.json from whatever's on disk
      (build-manifest!)

      (= cmd "all")
      (do (doseq [f formats] (download-format! f))
          (build-manifest!)
          (println "Done."))

      (some #{cmd} formats)
      (do (download-format! cmd)
          (build-manifest!)
          (println "Done."))

      :else
      (do (println (str "Unknown format: " cmd))
          (println (str "Formats: " (str/join ", " formats)))
          (System/exit 1)))))

(apply -main *command-line-args*)
