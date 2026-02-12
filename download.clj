#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def base-url "https://www.mtggoldfish.com")
(def scryfall-api "https://api.scryfall.com")
(def max-decks 30)
(def cache-file "card-cache.edn")

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

;; Card cache: {"Card Name" "SET:number", ...}
(def card-cache (atom {}))

(defn load-cache! []
  (when (.exists (java.io.File. cache-file))
    (reset! card-cache (edn/read-string (slurp cache-file)))))

(defn save-cache! []
  (let [sorted (into (sorted-map) @card-cache)]
    (spit cache-file
          (str "{\n"
               (str/join "\n" (map (fn [[k v]] (str " " (pr-str k) " " (pr-str v))) sorted))
               "\n}\n"))))

(defn fetch-page [url]
  (-> (http/get url {:headers {"User-Agent" "Mozilla/5.0"}})
      :body))

(defn scryfall-lookup [card-name]
  (Thread/sleep 100)
  (try
    (let [q    (str "!\"" card-name "\" new:language")
          url  (str scryfall-api "/cards/search?q=" (java.net.URLEncoder/encode q "UTF-8"))
          resp (http/get url {:headers {"User-Agent" "Mozilla/5.0"
                                        "Accept"     "application/json"}})
          data (json/parse-string (:body resp))
          card (first (get data "data"))]
      (when card
        (str (str/upper-case (get card "set")) ":" (get card "collector_number"))))
    (catch Exception e
      (println (str "      !! scryfall lookup failed for: " card-name " (" (.getMessage e) ")"))
      nil)))

(defn resolve-card [card-name]
  (if-let [cached (get @card-cache card-name)]
    cached
    (when-let [result (scryfall-lookup card-name)]
      (swap! card-cache assoc card-name result)
      (save-cache!)
      result)))

(defn parse-deck-line [line]
  (when-let [[_ qty name] (re-matches #"(\d+)\s+(.+)" line)]
    {:qty (Integer/parseInt qty) :name name}))

(defn format-xmage-line [{:keys [qty name]} card-info]
  (if card-info
    (str qty " [" card-info "] " name)
    (str qty " " name)))

(defn convert-deck! [path]
  (let [lines     (str/split-lines (slurp path))
        sideboard (atom false)
        converted (mapv (fn [line]
                          (if (str/blank? line)
                            (do (reset! sideboard true) nil)
                            (when-let [parsed (parse-deck-line line)]
                              (let [info   (resolve-card (:name parsed))
                                    formatted (format-xmage-line parsed info)]
                                (if @sideboard
                                  (str "SB: " formatted)
                                  formatted)))))
                        lines)]
    (spit path (str/join "\n" (remove nil? converted)))))

;; MTG Goldfish scraping

(defn extract-archetype-slugs
  [fmt html]
  (->> (re-seq (re-pattern (str "href=\"(/archetype/" fmt "-[^\"#]+)")) html)
       (map second)
       distinct))

(defn extract-deck-download-id
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
            path     (str "decks/" fmt "/" prefix "-" name ".dck")]
        (spit path deck-txt)
        (println (str "    -> converting to xmage format"))
        (convert-deck! path)
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
  (load-cache!)
  (println (str "Card cache: " (count @card-cache) " entries"))
  (let [fmt (first args)]
    (if fmt
      (if (some #{fmt} formats)
        (do (download-format! fmt)
            (println "\nDone."))
        (do (println (str "Unknown format: " fmt))
            (println (str "Available: " (str/join ", " formats)))
            (System/exit 1)))
      (do (println "Usage: bb download.clj <format>")
          (println (str "Available: " (str/join ", " formats)))
          (System/exit 1)))))

(apply -main *command-line-args*)
