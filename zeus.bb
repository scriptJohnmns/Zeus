#!/usr/bin/env bb
;; zeus — painel e controle de thresholds de carga da bateria (sysfs)

(ns zeus
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def bat-dir (or (System/getenv "ZEUS_BAT") "/sys/class/power_supply/BAT0"))
(def ac-dir  (or (System/getenv "ZEUS_AC")  "/sys/class/power_supply/AC"))

;; ---------------------------------------------------------------- cores

(def ^:dynamic *color* (and (System/console) (nil? (System/getenv "NO_COLOR"))))

(defn- sgr [code s] (if *color* (str "\u001b[" code "m" s "\u001b[0m") (str s)))
(def bold   (partial sgr "1"))
(def dim    (partial sgr "2"))
(def red    (partial sgr "31"))
(def green  (partial sgr "32"))
(def yellow (partial sgr "33"))
(def cyan   (partial sgr "36"))

;; ---------------------------------------------------------------- leitura

(defn- read-file [dir name]
  (let [f (io/file dir name)]
    (when (.exists f)
      (try (str/trim (slurp f)) (catch Exception _ nil)))))

(defn- read-num [dir name]
  (some-> (read-file dir name) parse-long))

(defn- selected-behaviour
  "charge_behaviour vem como \"[auto] inhibit-charge force-discharge\"."
  [s]
  (some->> s (re-find #"\[([^\]]+)\]") second))

(defn- charger
  "Fonte USB-C (ucsi) conectada, com potência máxima negociada. nil se for barrel/sem info."
  []
  (first
   (for [d (.listFiles (io/file "/sys/class/power_supply"))
         :when (and (= "USB" (read-file d "type")) (= 1 (read-num d "online")))
         :let [v (read-num d "voltage_max") a (read-num d "current_max")]
         :when (and v a (pos? v) (pos? a))]
     {:watts (/ (* v a) 1e12)
      :pd?   (str/includes? (or (read-file d "usb_type") "") "[PD")})))

(defn- system-temp
  "Temperatura do sistema em °C (o BAT0 não expõe sensor próprio)."
  []
  (let [hwmons (.listFiles (io/file "/sys/class/hwmon"))
        by-name (into {} (for [h hwmons] [(read-file h "name") h]))]
    (some (fn [n] (some-> (by-name n) (read-num "temp1_input") (/ 1000.0)))
          ["thinkpad" "acpitz" "coretemp"])))

(defn battery []
  (let [r #(read-num bat-dir %)]
    {:capacity    (r "capacity")
     :status      (read-file bat-dir "status")
     :start       (r "charge_control_start_threshold")
     :end         (r "charge_control_end_threshold")
     :behaviour   (selected-behaviour (read-file bat-dir "charge_behaviour"))
     :cycles      (r "cycle_count")
     :energy-now  (r "energy_now")
     :energy-full (r "energy_full")
     :energy-des  (r "energy_full_design")
     :power       (r "power_now")
     :voltage     (r "voltage_now")
     :vmin-design (r "voltage_min_design")
     :maker       (read-file bat-dir "manufacturer")
     :model       (read-file bat-dir "model_name")
     :tech        (read-file bat-dir "technology")
     :ac?         (= 1 (read-num ac-dir "online"))
     :charger     (charger)
     :temp        (system-temp)}))

;; ---------------------------------------------------------------- cálculos

(defn- micro->unit [n] (when n (/ n 1e6)))

(defn health [{:keys [energy-full energy-des]}]
  (when (and energy-full energy-des (pos? energy-des))
    (* 100.0 (/ energy-full energy-des))))

(defn eta-minutes
  "Minutos até cheio (ou até o threshold) quando carregando, até vazio quando descarregando."
  [{:keys [status energy-now energy-full power end]}]
  (when (and energy-now energy-full power (pos? power))
    (case status
      "Charging"    (let [target (* energy-full (/ (or end 100) 100.0))]
                      (when (> target energy-now)
                        (* 60 (/ (- target energy-now) power))))
      "Discharging" (* 60 (/ energy-now power))
      nil)))

(defn- fmt-duration [mins]
  (let [m (Math/round (double mins))]
    (format "%dh%02dm" (quot m 60) (mod m 60))))

(defn mode [{:keys [start end]}]
  (cond
    (nil? end)  :desconhecido
    (>= end 100) :viagem
    (= [start end] [75 80]) :tomada
    :else :custom))

;; ---------------------------------------------------------------- autonomia

(def ^:private drain-file
  (io/file (or (System/getenv "XDG_CACHE_HOME") (str (System/getProperty "user.home") "/.cache"))
           "zeus" "drain.edn"))

(def ^:private drain-tau-s
  "Constante de tempo da média móvel: ~10 min de uso na bateria dominam a média."
  600.0)

(defn- load-drain []
  (try (when (.exists drain-file) (read-string (slurp drain-file)))
       (catch Exception _ nil)))

(defn record-drain!
  "Atualiza a média de consumo (W) quando está descarregando. power_now oscila
   muito, então a autonomia usa essa média em vez do valor instantâneo.
   Retorna a média atual (ou nil se nunca rodou na bateria)."
  [{:keys [status power]}]
  (let [prev (load-drain)
        now  (System/currentTimeMillis)
        w    (micro->unit power)]
    (if (and (= status "Discharging") w (pos? w))
      (let [dt    (if prev (min 60.0 (/ (- now (:at prev)) 1000.0)) 0.0)
            alpha (if prev (- 1.0 (Math/exp (- (/ dt drain-tau-s)))) 1.0)
            avg   (if prev (+ (:avg-w prev) (* alpha (- w (:avg-w prev)))) w)]
        (try (io/make-parents drain-file)
             (spit drain-file (pr-str {:avg-w avg :at now}))
             (catch Exception _ nil))
        avg)
      (:avg-w prev))))

;; ---------------------------------------------------------------- painel

(def bar-width 40)

(defn- level-color [pct]
  (cond (<= pct 20) red (<= pct 50) yellow :else green))

(defn- bar
  "[########################|.......]  — o | marca o limite de carga.
   Com tick (menu), um pulso anda pela barra: > entrando, < saindo."
  [pct end status tick]
  (let [filled (int (Math/round (* bar-width (/ pct 100.0))))
        mark   (when (and end (< end 100))
                 (int (Math/round (* bar-width (/ end 100.0)))))
        target (or mark bar-width)
        pulse  (when tick
                 (case status
                   "Charging"    (when (< filled target)
                                   [(+ filled (mod tick (- target filled))) (bold (green ">"))])
                   "Discharging" (when (pos? filled)
                                   [(- filled 1 (mod tick filled)) (bold (yellow "<"))])
                   nil))
        cells  (for [i (range bar-width)]
                 (cond
                   (= i (first pulse)) (second pulse)
                   (= i mark)   (cyan "|")
                   (< i filled) ((level-color pct) "#")
                   :else        (dim ".")))]
    (str (dim "[") (apply str cells) (dim "]"))))

(defn- status-label [{:keys [status ac?]}]
  (case status
    "Charging"     (str (green "CHARGING") (dim "  bateria recebendo carga"))
    "Discharging"  (str (yellow "DISCHARGING") (dim "  notebook rodando na bateria"))
    "Full"         (str (green "FULL") (dim "  carga completa"))
    "Not charging" (str (cyan "IDLE") (dim (if ac? "  na tomada, carga travada pelo threshold"
                                               "  sem carregar")))
    (dim (str/upper-case (or status "?")))))

(defn- mode-label [{:keys [start end] :as b}]
  (let [desc (dim (format "  volta a carregar abaixo de %s%%, para em %s%%" start end))]
    (case (mode b)
      :viagem (str (yellow "viagem") (dim "  carrega ate 100%"))
      :tomada (str (cyan "tomada") desc)
      :custom (str (cyan "custom") desc)
      (dim "??"))))

(defn- source-label [{:keys [ac? charger]}]
  (if-not ac?
    (yellow "desconectada")
    (str (green "conectada")
         (when-let [{:keys [watts pd?]} charger]
           (dim (format "  USB-C%s %.0fW max" (if pd? " PD" "") watts))))))

(defn- flow-label
  "power_now é o fluxo da bateria (entrando/saindo), não o consumo do notebook."
  [{:keys [status power end] :as b}]
  (let [w   (or (micro->unit power) 0.0)
        eta (let [m (eta-minutes b)] (when (and m (>= m 1)) m))]
    (case status
      "Charging"    (str (green (format "+%.1f W" w)) (dim " entrando")
                         (when eta (dim (str "  eta " (fmt-duration eta) " -> " (or end 100) "%"))))
      "Discharging" (str (yellow (format "-%.1f W" w)) (dim " saindo"))
      (str (cyan (format "%.1f W" w)) (dim "  parada, notebook alimentado direto pela fonte")))))

(defn- stress
  "Estimativa qualitativa: Li-ion envelhece mais rápido parada em carga alta
   (envelhecimento por calendário) e quando descarrega muito."
  [{:keys [capacity status ac?]}]
  (let [pct (or capacity 0)]
    (cond
      (<= pct 15)                (str (red "alto") (dim "  carga muito baixa, coloque na tomada"))
      (and (>= pct 90) ac? (not= status "Discharging"))
                                 (str (yellow "medio") (dim "  parada em carga alta, ideal ~80% na tomada"))
      (>= pct 90)                (str (green "baixo") (dim "  carga alta, mas em uso"))
      :else                      (str (green "baixo") (dim "  faixa ideal de carga")))))

(defn- autonomy-label [{:keys [status energy-now energy-full]} avg-w]
  (cond
    (nil? avg-w)
    (dim "sem historico ainda, use um pouco na bateria pra calibrar")

    (= status "Discharging")
    (str (bold (fmt-duration (* 60 (/ (micro->unit energy-now) avg-w))))
         (dim (format " restantes  media %.1f W" avg-w)))

    :else
    (str (fmt-duration (* 60 (/ (micro->unit energy-now) avg-w)))
         (dim " fora da tomada")
         (dim (format "  cheia: %s  media de uso %.1f W"
                      (fmt-duration (* 60 (/ (micro->unit energy-full) avg-w))) avg-w)))))

(defn- cells
  "Número de células em série, estimado pela tensão mínima de projeto (~3.85V nominal/célula)."
  [{:keys [vmin-design]}]
  (when (and vmin-design (pos? vmin-design))
    (max 1 (Math/round (/ vmin-design 3.85e6)))))

(defn- row [label value]
  (str " " (dim ">") " " (format "%-10s" label) value))

(defn- rule [] (dim (str " " (apply str (repeat (+ bar-width 8) "-")))))

(defn render [b & {:keys [tick]}]
  (let [avg-w (record-drain! b)
        {:keys [capacity cycles energy-now energy-full energy-des voltage maker model tech behaviour temp]} b
        pct (or capacity 0)
        h   (health b)
        n   (cells b)]
    (str/join
     "\n"
     (remove nil?
             [(str " " (bold (green "zeus")) (dim " :: ") "BAT0 " (dim (str "[" maker " " model "]")))
              (rule)
              (str " " (bar pct (:end b) (:status b) tick) " " (bold ((level-color pct) (format "%3d%%" pct))))
              (rule)
              (row "status" (status-label b))
              (row "fonte" (source-label b))
              (row "modo" (mode-label b))
              (when (and behaviour (not= behaviour "auto"))
                (row "behaviour" (yellow behaviour)))
              (rule)
              (row "carga" (str (format "%d%%" pct)
                                (dim (format "  %.1f Wh guardados" (or (micro->unit energy-now) 0.0)))))
              (row "fluxo" (flow-label b))
              (row "autonomia" (autonomy-label b avg-w))
              (row "estresse" (stress b))
              (rule)
              (row "saude" (str (if h ((cond (< h 70) red (< h 85) yellow :else green)
                                       (format "%.1f%%" h)) "?")
                                (when (and energy-full energy-des)
                                  (dim (format "  %.1f de %.1f Wh de fabrica (-%.1f Wh)"
                                               (micro->unit energy-full) (micro->unit energy-des)
                                               (micro->unit (- energy-des energy-full)))))))
              (row "ciclos" (str cycles (dim "  ciclos completos de carga")))
              (row "tensao" (str (format "%.2f V" (or (micro->unit voltage) 0.0))
                                 (when (and n voltage)
                                   (dim (format "  %.2f V/celula (%dS)" (/ (micro->unit voltage) n) n)))))
              (when temp
                (row "temp" (str ((cond (>= temp 70) red (>= temp 50) yellow :else green)
                                  (format "%.0f C" temp))
                                 (dim "  sistema (bateria nao tem sensor exposto)"))))
              (row "quimica" (str tech))]))))

;; ---------------------------------------------------------------- escrita

(defn- sysfs-path [name] (str bat-dir "/" name))

(defn- die [msg code]
  (binding [*out* *err*] (println (str (red "[err]") " " msg)))
  (System/exit code))

(defn apply-thresholds!
  "Grava start/end numa única chamada sudo (uma autenticação só).
   A ordem importa: o kernel rejeita start >= end, então se o end vai subir
   ele é gravado primeiro; se vai descer, o start desce antes.
   Retorna nil no sucesso ou a mensagem de erro."
  [start end & {:keys [behaviour]}]
  (if-not (and start end (< 0 start end 101))
    (str "thresholds invalidos: start=" start " stop=" end
         " (precisa 0 < start < stop <= 100)")
    (let [cur-end (or (read-num bat-dir "charge_control_end_threshold") 100)
          w       (fn [v f] (format "echo %d > %s" v (sysfs-path f)))
          writes  (if (> end cur-end)
                    [(w end "charge_control_end_threshold") (w start "charge_control_start_threshold")]
                    [(w start "charge_control_start_threshold") (w end "charge_control_end_threshold")])
          writes  (cond-> writes
                    behaviour (conj (format "echo %s > %s" behaviour (sysfs-path "charge_behaviour"))))
          {:keys [exit]} (p/shell {:continue true} "sudo" "sh" "-c" (str/join " && " writes))]
      (when-not (zero? exit)
        "falha ao gravar os thresholds"))))

(defn write-thresholds! [start end & opts]
  (when-let [err (apply apply-thresholds! start end opts)]
    (die err 1)))

;; ---------------------------------------------------------------- menu

(def logo
  ["███████╗███████╗██╗   ██╗███████╗"
   "╚══███╔╝██╔════╝██║   ██║██╔════╝"
   "  ███╔╝ █████╗  ██║   ██║███████╗"
   " ███╔╝  ██╔══╝  ██║   ██║╚════██║"
   "███████╗███████╗╚██████╔╝███████║"
   "╚══════╝╚══════╝ ╚═════╝ ╚══════╝"])

(def bolt
  ["     ___ "
   "    /  / "
   "   /  /__"
   "  /__   /"
   "     / / "
   "    //   "])

(defn- logo-lines
  "Logo + raio. A cada ~9s cai um raio: o logo pisca amarelo por 2 frames."
  [tick]
  (let [strike? (< (mod tick 60) 2)
        glow?   (< (mod tick 60) 6)
        paint-l (if strike? (comp bold yellow) green)
        paint-b (cond strike? (comp bold yellow) glow? yellow :else dim)]
    (map (fn [l b] (str " " (paint-l l) "  " (paint-b b))) logo bolt)))

(def options
  [{:key "1" :label "modo tomada" :hint "start=75 stop=80"  :action :tomada}
   {:key "2" :label "modo viagem" :hint "start=99 stop=100" :action :viagem}
   {:key "3" :label "custom"      :hint "start/stop manual" :action :custom}
   {:key "4" :label "atualizar"   :hint "reler o sysfs"     :action :refresh}
   {:key "q" :label "sair"        :hint ""                  :action :quit}])

(def ^:private esc "\u001b[")
(def ^:private saved-stty (atom nil))

(defn- stty
  "Chama stty direto via ProcessBuilder: o p/shell registra shutdown hook
   próprio e quebra quando chamado durante o desligamento do bb."
  [& args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (into ["stty"] args))
             (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/tty")))
             (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD))
        proc (.start pb)
        out  (slurp (.getInputStream proc))]
    (.waitFor proc)
    out))

(defn- raw-mode! []
  ;; -isig: Ctrl+C chega como byte 0x03 e sai pelo caminho normal
  (stty "-icanon" "-echo" "-isig" "min" "1")
  ;; mouse SGR (1000+1006) e cursor escondido
  (print (str esc "?1000h" esc "?1006h" esc "?25l"))
  (flush))

(defn- cooked-mode! []
  (print (str esc "?1000l" esc "?1006l" esc "?25h"))
  (flush)
  (stty @saved-stty))

(defn- read-available []
  (let [n (.available System/in)]
    (when (pos? n)
      (let [buf (byte-array n)
            r   (.read System/in buf 0 n)]
        (String. buf 0 r "UTF-8")))))

(defn- read-line* []
  (loop [sb (StringBuilder.)]
    (let [c (.read System/in)]
      (if (or (= c -1) (= c 10))
        (str sb)
        (recur (.append sb (char c)))))))

(defn- parse-keys
  "Bytes crus -> [:up :down :enter [:click row] \"1\" ...]."
  [s]
  (loop [s s acc []]
    (cond
      (empty? s) acc

      (str/starts-with? s "\u001b[<")
      (if-let [[m btn _x y kind] (re-find #"^\u001b\[<(\d+);(\d+);(\d+)([Mm])" s)]
        (recur (subs s (count m))
               (cond-> acc (and (= kind "M") (= btn "0")) (conj [:click (parse-long y)])))
        acc)

      (str/starts-with? s "\u001b[A") (recur (subs s 3) (conj acc :up))
      (str/starts-with? s "\u001b[B") (recur (subs s 3) (conj acc :down))
      (str/starts-with? s "\u001b")   (recur (subs s 1) acc)
      (#{\return \newline} (first s)) (recur (subs s 1) (conj acc :enter))
      (#{\u0003 \u0004} (first s))    (recur (subs s 1) (conj acc "q"))
      :else (recur (subs s 1) (conj acc (str (first s)))))))

(defn- frame
  "Linhas da tela + a linha (1-based) da primeira opção, pros cliques."
  [b sel msg tick]
  (let [head  (concat [""]
                      (logo-lines tick)
                      [(dim " battery charge control // thresholds via sysfs") ""])
        top   (vec (concat head (str/split-lines (render b :tick tick)) [(rule)]))
        opts  (map-indexed
               (fn [i {:keys [key label hint]}]
                 (let [line (format " %s [%s] %-12s %s" (if (= i sel) ">" " ") key label hint)]
                   (if (= i sel) (bold (green line)) line)))
               options)]
    {:lines   (vec (concat top opts
                           [(rule) (str " " (or msg ""))
                            (dim " [j/k] navegar  [enter] ok  [1-4] atalho  [q] sair")]))
     :opt-row (inc (count top))}))

(defn- draw! [lines]
  (print (str esc "H" (str/join "\n" (map #(str % esc "K") lines)) esc "J"))
  (flush))

(defn- with-cooked
  "Sai do modo raw pra rodar sudo / ler input, depois volta."
  [st f]
  (cooked-mode!)
  (print (str esc (inc (count (:lines st))) ";1H" esc "J"))
  (flush)
  (let [result (f)]
    (raw-mode!)
    (print (str esc "2J"))
    result))

(defn- result-msg [err ok]
  (if err (str (red "[err]") " " err) (str (green "[ok]") " " ok)))

(defn- activate [st i]
  (let [st (assoc st :sel i)]
    (case (:action (options i))
      :tomada  (assoc st :msg (result-msg (with-cooked st #(apply-thresholds! 75 80)) "travado em 80%"))
      :viagem  (assoc st :msg (result-msg (with-cooked st #(apply-thresholds! 99 100 :behaviour "auto"))
                                          "liberado ate 100%"))
      :custom  (let [[s e err] (with-cooked st
                                 (fn []
                                   (print " start stop > ") (flush)
                                   (let [[s e] (map parse-long (re-seq #"\d+" (read-line*)))]
                                     [s e (apply-thresholds! s e)])))]
                 (assoc st :msg (result-msg err (str "thresholds " s "-" e "%"))))
      :refresh (assoc st :msg (result-msg nil "atualizado"))
      :quit    (assoc st :quit true))))

(defn- handle [st k]
  (let [n    (count options)
        hot  (first (keep-indexed (fn [i o] (when (= k (:key o)) i)) options))]
    (cond
      (#{:up "k"} k)   (update st :sel #(mod (dec %) n))
      (#{:down "j"} k) (update st :sel #(mod (inc %) n))
      (= k :enter)     (activate st (:sel st))
      (vector? k)      (let [i (- (second k) (:opt-row st))]
                         (if (< -1 i n) (activate st i) st))
      hot              (activate st hot)
      :else            st)))

(def ^:private cleaned? (atom false))

(defn- restore-terminal! []
  (when (compare-and-set! cleaned? false true)
    (print (str esc "?1049l"))
    (cooked-mode!)))

(def ^:private frame-ms 150)
(def ^:private read-ms 2000)

(defn menu []
  (reset! saved-stty (str/trim (stty "-g")))
  (print (str esc "?1049h")) ; tela alternativa
  (raw-mode!)
  ;; rede de segurança pra SIGTERM / Ctrl+C durante sudo (modo cooked)
  (.addShutdownHook (Runtime/getRuntime) (Thread. restore-terminal!))
  (loop [st {:sel 0 :msg nil :lines [] :opt-row 0 :tick 0 :b nil :read-at 0}]
    (let [ks  (some-> (read-available) parse-keys)
          st  (reduce (fn [st k] (if (:quit st) st (handle st k))) st ks)
          now (System/currentTimeMillis)]
      (if (:quit st)
        (do (restore-terminal!) (System/exit 0))
        ;; relê o sysfs a cada 2s (ou logo após uma ação); anima a cada 150ms
        (let [fresh? (or (seq ks) (nil? (:b st)) (>= now (+ (:read-at st) read-ms)))
              st     (cond-> st fresh? (assoc :b (battery) :read-at now))
              {:keys [lines opt-row]} (frame (:b st) (:sel st) (:msg st) (:tick st))]
          (draw! lines)
          (Thread/sleep frame-ms)
          (recur (assoc st :lines lines :opt-row opt-row :tick (inc (:tick st)))))))))

;; ---------------------------------------------------------------- comandos

(defn show [] (println) (println (render (battery))) (println))

(defn watch [secs]
  (let [clear (if *color* "\u001b[H\u001b[2J" "")]
    (when *color*
      (print "\u001b[?25l") ; esconde o cursor
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(do (print "\u001b[?25h") (flush)))))
    (loop []
      (print clear)
      (println)
      (println (render (battery)))
      (println)
      (println (dim (str " refresh " secs "s // ^C pra sair")))
      (flush)
      (Thread/sleep (* 1000 secs))
      (recur))))

(def usage
  (str (bold (green "zeus")) (dim " :: controle de carga da bateria") "\n\n"
       "  zeus              menu interativo (painel se nao for terminal)\n"
       "  zeus status       painel\n"
       "  zeus 80           modo tomada  (start=75 stop=80)\n"
       "  zeus 100          modo viagem  (start=99 stop=100)\n"
       "  zeus set S E      thresholds custom (ex: zeus set 55 60)\n"
       "  zeus watch [N]    painel ao vivo, refresh a cada N s (padrao 2)\n"
       "  zeus help         esta ajuda\n\n"
       (dim "  env: ZEUS_BAT, ZEUS_AC (caminhos no sysfs), NO_COLOR")))

(defn- after-write [msg]
  (println (str (green "[ok]") " " msg))
  (show))

(defn -main [& args]
  (let [[cmd & more] args]
    (case cmd
      nil     (if (System/console) (menu) (show))
      "menu"  (menu)
      "status" (show)
      "80"    (do (write-thresholds! 75 80) (after-write "travado em 80%"))
      "100"   (do (write-thresholds! 99 100 :behaviour "auto") (after-write "liberado ate 100%"))
      "set"   (let [[s e] (map parse-long more)]
                (if (and s e)
                  (do (write-thresholds! s e) (after-write (str "thresholds " s "-" e "%")))
                  (do (println usage) (System/exit 1))))
      "watch" (watch (or (some-> (first more) parse-long) 2))
      ("help" "-h" "--help") (println usage)
      (do (println usage) (System/exit 1)))))

(apply -main *command-line-args*)
