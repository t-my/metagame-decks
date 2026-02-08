#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[clojure.string :as str])

(def base-url "https://www.mtggoldfish.com")
(def max-decks 30)

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

(defn fetch-page [url]
  (-> (http/get url {:headers {"User-Agent" "Mozilla/5.0"}})
      :body))

(defn extract-archetype-slugs
  "Parse the metagame page HTML to find unique /archetype/{format}-* links."
  [fmt html]
  (->> (re-seq (re-pattern (str "href=\"(/archetype/" fmt "-[^\"#]+)")) html)
       (map second)
       distinct))

(defn extract-deck-download-id
  "Parse an archetype page to find the /deck/download/{id} link."
  [html]
  (some->> (re-find #"href=\"/deck/download/(\d+)\"" html)
           second))

(defn slug->name [fmt slug]
  (-> slug
      (str/replace (re-pattern (str "^/archetype/" fmt "-")) "")
      (str/replace #"-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" "")))

(defn download-deck! [fmt idx slug]
  (let [name     (slug->name fmt slug)
        prefix   (format "%02d" idx)
        url      (str base-url slug "#paper")
        _        (println (str "  [" prefix "] Fetching archetype: " name))
        html     (fetch-page url)
        deck-id  (extract-deck-download-id html)]
    (if deck-id
      (let [deck-txt (fetch-page (str base-url "/deck/download/" deck-id))
            path     (str "decks/" fmt "/" prefix "-" name ".txt")]
        (spit path deck-txt)
        (println (str "    -> saved " path)))
      (println (str "    !! no download link found for " name)))))

(defn download-format! [fmt]
  (let [dir (str "decks/" fmt)]
    (.mkdirs (java.io.File. dir))
    (println (str "\n=== " (str/upper-case fmt) " ==="))
    (println (str "Fetching " fmt " metagame page..."))
    (let [url   (str base-url "/metagame/" fmt "/full#paper")
          html  (fetch-page url)
          slugs (take max-decks (extract-archetype-slugs fmt html))]
      (println (str "Found " (count slugs) " archetypes (capped at " max-decks ")"))
      (doseq [[idx slug] (map-indexed #(vector (inc %1) %2) slugs)]
        (download-deck! fmt idx slug)
        (Thread/sleep 1000)))))

(defn -main [& args]
  (let [fmt (first args)]
    (if fmt
      (if (some #{fmt} formats)
        (do (download-format! fmt)
            (println "\nDone."))
        (do (println (str "Unknown format: " fmt))
            (println (str "Available: " (str/join ", " formats)))
            (System/exit 1)))
      (do (println "Usage: bb download_legacy.clj <format>")
          (println (str "Available: " (str/join ", " formats)))
          (System/exit 1)))))

(apply -main *command-line-args*)
