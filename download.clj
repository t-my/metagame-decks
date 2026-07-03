#!/usr/bin/env bb

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def base-url "https://www.mtgtop8.com")
(def max-decks 25)

;; Formats we publish, mapped to their MTGTop8 format code. Constructed formats use the
;; archetype-metagame path (format page lists archetypes + %); commander formats have no archetype
;; structure on MTGTop8 (event-only), so they use an event-based path — see commander-decks.
;; MTGTop8 has no Timeless / Penny Dreadful / Brawl, so those are dropped. "commander" maps to cEDH.
(def formats
  [{:id "standard"       :code "ST"}
   {:id "modern"         :code "MO"}
   {:id "pioneer"        :code "PI"}
   {:id "historic"       :code "HI"}
   {:id "explorer"       :code "EXP"}
   {:id "alchemy"        :code "ALCH"}
   {:id "pauper"         :code "PAU"}
   {:id "legacy"         :code "LE"}
   {:id "vintage"        :code "VI"}
   {:id "premodern"      :code "PREM"}
   {:id "duel_commander" :code "EDH"  :commander true}
   {:id "commander"      :code "cEDH" :commander true}])

(def format-ids (mapv :id formats))
(def format-by-id (into {} (map (juxt :id identity)) formats))

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

;; --- MTGTop8 scraping ---

(def ^:private polite-ms 250)   ; pause between requests

;; Decode the handful of HTML entities MTGTop8 puts in archetype/deck display names. Card names in the
;; mtgo export are plain text, so this is only for labels.
(defn- html-unescape [s]
  (-> s
      (str/replace "&amp;" "&")
      (str/replace #"&#0?39;" "'")
      (str/replace "&apos;" "'")
      (str/replace "&quot;" "\"")
      (str/replace "&rarr;" "")
      (str/replace #"&[a-zA-Z]+;" " ")))

(defn- clean-name [s]
  (-> (str/replace s #"<[^>]+>" "") html-unescape str/trim))

;; Tidy a decklist: normalize line endings, collapse blank-line runs (e.g. left by dropping the
;; "Sideboard" marker) to a single separator, trim, and end with one newline.
(defn normalize-txt [s]
  (-> s
      (str/replace "\r\n" "\n") (str/replace "\r" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim
      (str "\n")))

;; MTGTop8's mtgo export is community-standard MTGO text ("<qty> <card name>" per line) but uses a
;; literal "Sideboard" line where MTGO text uses a blank line — so swap it, then normalize. For
;; commander decks the export already isolates the commander in that Sideboard section, so the result
;; is a valid commander list (main + blank + commander) with no extra work.
(defn fetch-deck-txt [deck-id]
  (-> (fetch-page (str base-url "/mtgo?d=" deck-id))
      (str/replace #"(?im)^sideboard[ \t]*$" "")
      normalize-txt))

;; --- constructed formats: archetype + metagame % ---

;; The format page lists each metagame archetype as `<a href=archetype?a=ID&meta=M&f=CODE>NAME</a>`
;; followed by its share `class=S14>NN %`. The page groups archetypes by macro-category (Aggro /
;; Control / …), not by overall %, so we sort by % ourselves. "Other - …" catch-alls are dropped.
(defn parse-archetypes [code html]
  (let [pat (re-pattern (str "<a href=archetype\\?a=(\\d+)&meta=\\d+&f=" code ">(.*?)</a>[\\s\\S]{0,240}?class=S14>([\\d.]+) ?%"))]
    (->> (re-seq pat html)
         (map (fn [[_ a nm pct]] {:a a :name (clean-name nm) :pct (Double/parseDouble pct)}))
         (remove #(re-find #"(?i)^other\b" (:name %)))
         (distinct)
         (sort-by :pct >))))

;; First (most recent) deck listed on an archetype page.
(defn archetype-first-deck [code a]
  (let [html (fetch-page (str base-url "/archetype?a=" a "&f=" code))]
    (second (re-find (re-pattern (str "&(?:amp;)?d=(\\d+)&(?:amp;)?f=" code)) html))))

(defn constructed-decks
  "Seq of {:name :txt} for the top `n` archetypes of a constructed format, by metagame share."
  [code n]
  (let [html  (fetch-page (str base-url "/format?f=" code))
        archs (take n (parse-archetypes code html))]
    (println (str "Found " (count (parse-archetypes code html)) " archetypes (using top " (count archs) ")"))
    (keep (fn [{:keys [a name]}]
            (Thread/sleep polite-ms)
            (println (str "  " name))
            (if-let [did (archetype-first-deck code a)]
              (do (Thread/sleep polite-ms)
                  (let [txt (fetch-deck-txt did)]
                    (if (re-find #"\S" txt) {:name name :txt txt}
                        (do (println (str "    !! empty decklist for " name)) nil))))
              (do (println (str "    !! no deck found for " name)) nil)))
          archs)))

;; --- commander formats: event-based (no archetype/% structure on MTGTop8) ---

;; The commander name(s) of a fetched list: the sideboard section (after the blank line) holds the
;; commander (and partner, if any), one per line. Joined with " / " for a display label.
(defn- commander-name [txt]
  (let [parts (str/split txt #"\n\n" 2)
        side  (when (> (count parts) 1) (str/split-lines (str/trim (second parts))))
        names (keep #(second (re-find #"^\d+\s+(.*\S)\s*$" %)) side)]
    (when (seq names) (str/join " / " names))))

(defn commander-decks
  "Seq of {:name :txt} for commander formats: walk the format page's recent events, take their decks
   in placement order, dedupe by commander for variety, until `n` are collected."
  [code n]
  (let [fmt-html (fetch-page (str base-url "/format?f=" code))
        events   (distinct (map second (re-seq (re-pattern (str "event\\?e=(\\d+)&f=" code)) fmt-html)))]
    (loop [evs events, acc [], seen #{}]
      (if (or (>= (count acc) n) (empty? evs))
        (take n acc)
        (let [e       (first evs)
              ev-html (fetch-page (str base-url "/event?e=" e "&f=" code))
              dids    (distinct (map second (re-seq (re-pattern (str "&(?:amp;)?d=(\\d+)&(?:amp;)?f=" code)) ev-html)))]
          (Thread/sleep polite-ms)
          (let [[acc' seen']
                (reduce (fn [[a s] did]
                          (if (>= (count a) n)
                            (reduced [a s])
                            (do (Thread/sleep polite-ms)
                                (let [txt (fetch-deck-txt did)
                                      nm  (commander-name txt)]
                                  (if (and nm (re-find #"\S" txt) (not (s nm)))
                                    (do (println (str "  " nm)) [(conj a {:name nm :txt txt}) (conj s nm)])
                                    [a s])))))
                        [acc seen] dids)]
            (recur (rest evs) acc' seen')))))))

;; Filesystem-safe slug for a deck filename: lowercase, punctuation -> dash.
(defn- slugify [name]
  (-> name str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn clear-dir! [dir]
  (let [d (java.io.File. dir)]
    (when (.isDirectory d)
      (doseq [f (.listFiles d)] (.delete f)))
    (.mkdirs d)))

(defn download-format! [id]
  (let [{:keys [code commander]} (format-by-id id)
        dir (str "decks/" id)]
    (clear-dir! dir) ; drop archetypes that fell out of the metagame this week
    (println (str "\n=== " (str/upper-case id) " (mtgtop8 " code ") ==="))
    (let [decks (if commander (commander-decks code max-decks) (constructed-decks code max-decks))]
      (doseq [[idx {:keys [name txt]}] (map-indexed #(vector (inc %1) %2) decks)]
        (let [path (str dir "/" (format "%02d" idx) "-" (slugify name) ".txt")]
          (spit path txt)
          (println (str "    -> " path)))))))

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
          (println (str "Formats: " (str/join ", " format-ids)))
          (System/exit 1))

      (= cmd "manifest")           ; regenerate manifest.json from whatever's on disk
      (build-manifest!)

      (= cmd "all")
      (do (doseq [id format-ids] (download-format! id))
          (build-manifest!)
          (println "Done."))

      (some #{cmd} format-ids)
      (do (download-format! cmd)
          (build-manifest!)
          (println "Done."))

      :else
      (do (println (str "Unknown format: " cmd))
          (println (str "Formats: " (str/join ", " format-ids)))
          (System/exit 1)))))

(apply -main *command-line-args*)
