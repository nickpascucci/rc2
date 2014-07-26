(ns rc2.state
  (:require [rc2.api :as api]
            [rc2.draw :as draw]
            [rc2.util :as util]
            [clojure.set :as set]))

(declare plan-route!)

(def heartbeat-interval (* 1000 3))
(def heartbeat-timeout (* heartbeat-interval 3))
(def task-update-interval 500) ;; Check for task state changes every 500ms.

;; Keybindings for moving between primary modes.
(def mode-keys {:delete {\I :insert}
                :insert {\D :delete
                         \E :edit}
                :edit {\newline :insert
                       \return :insert
                       \formfeed :insert}})

;; Keybindings for changing secondary mode.
(def sub-mode-keys {:delete {:default nil}
                    :insert {\P :source
                             \S :sink
                             :default :sink}})

(def default-animation-state {:index 0
                              :offsets (util/->world 0 0)})
(def animation-step-distance 5)

(def app-state
  (atom {
         :mouse {:location util/origin
                 :buttons {0 :up 1 :up 2 :up}
                 :previous-buttons {0 :up 1 :up 2 :up}}
         :keyboard {:pressed #{}
                    :previous-pressed #{}}
         :route {:waypoints []
                 :plan []
                 :animation default-animation-state
                 :execution {:current 0}}
         :parts {0 {:name "DEFAULT" :highlight true}}
         :ui {
              ;; Buttons have a name (rendered on the screen), a target vector, and a
              ;; transform function. When the button is clicked the transform function
              ;; is called with the value in this state tree at the 'target' path,
              ;; which is then replaced with the returned value.
              :buttons [{:text "Plan" :target [:route :waypoints] :hover false :click false
                         :xform (fn [waypoints]
                                  (plan-route! waypoints)
                                  waypoints)}
                        {:text "Start" :target [:running] :hover false :click false
                         :xform (constantly true)}
                        {:text "Stop" :target [:running] :hover false :click false
                         :xform (constantly false)}
                        {:text "Clear" :target [:route] :hover false :click false
                         :xform (fn [route] (assoc route :waypoints [] :plan []))}]}
         :mode {:primary  :insert
                :secondary :sink}
         :robot {:position (util/->world 0 0)}
         :tasks {:pending []
                 :complete []
                 :last-poll 0}
         :connection {:last-heartbeat 0 :connected false}
         :time 0
         }))

;;;;;;;;;;
;; State

;; Add new handlers for state paths in {pre,pos}-draw-transforms. State transform functions are
;; registered along with an input path and an output path. The function is applied to the current
;; (last-frame) state of the input and output paths and the returned value is stored in the output
;; path.

(defn merge-maps [result latter]
  "Merge two maps into one, preserving overall structure."
  (if (and (map? result) (map? latter))
    (merge-with merge-maps result latter)
    latter))

(defn apply-state-transforms [state transforms]
  "Apply a series of transforms of the form [in-path out-path transform] to a state map and return
  the updated map."
  (reduce (fn [prev-state [in-path out-path xform]]
            (let [xform-state (xform (get-in prev-state in-path)
                                     (get-in prev-state out-path))]
              (if (empty? out-path)
                xform-state
                (assoc-in prev-state out-path xform-state))))
          state
          transforms))

(defn copy [in out] in)

(defn debug-print [in out]
  (.log js/console "debug-print in:" (str in) "out:" (str out))
  out)

(defn current-time [] (.getTime (js/Date.)))

(defn highlighted? [m]
  (:highlight m))

(defn clicked? [mouse button]
  (and (= :down (get (:buttons mouse) button))
       (= :up (get (:previous-buttons mouse) button))))

(defn get-selected-part-id [parts]
  (first (first (filter (fn [[k v]] (highlighted? v)) parts))))

(defn handle-waypoint-updates [state route]
  (let [mouse (:mouse state)
        buttons (get-in state [:ui :buttons])
        waypoints (:waypoints state)]
    (if (and (not (some #(:hover %) buttons))
             (clicked? mouse 0))
      (cond
       (and (= :insert (get-in state [:mode :primary]))
            (get-selected-part-id (:parts state)))
       (update-in route [:waypoints]
                  conj {:location (:location mouse)
                        :highlight true
                        :kind (get-in state [:mode :secondary])
                        :part-id (get-selected-part-id (:parts state))})
       (= :delete (get-in state [:mode :primary]))
       (-> route
           (update-in [:waypoints] (fn [wps] (filter #(not (:highlight %)) wps)))
           (assoc :plan []))
       :else route)
      route)))

(defn highlight-waypoints [mouse waypoints]
  (let [mouse (util/canvas->world mouse)]
    (map (fn [wp]
           (assoc wp :highlight (< (util/distance (:location wp) mouse)
                                   (+ draw/waypoint-radius 10))))
         waypoints)))

(defn in-button? [btns btn pos]
  (let [{:keys [coord width height]} (draw/button-render-details btns btn)
        {bx :x by :y} (util/canvas->world coord)
        {:keys [x y]} (util/canvas->world pos)]
    (and (< bx x (+ bx width))
         (< (- by height) y by))))

(defn update-button-hover [mouse-pos buttons]
  (mapv (fn [btn] (assoc btn :hover (in-button? buttons btn mouse-pos))) buttons))

(defn update-button-click [mouse buttons]
  "Update the clicked state of UI buttons."
  (mapv (fn [btn] (assoc btn :click (and (clicked? mouse 0) (:hover btn)))) buttons))

(defn handle-button-actions [buttons state]
  "Perform the on-click actions of the clicked UI buttons."
  (let [transforms (filterv
                    (comp not nil?)
                    (mapv (fn [btn] [(:target btn) (:target btn) (:xform btn)])
                          (filterv #(:click %) buttons)))]
    (apply-state-transforms state transforms)))

(defn handle-part-keys [keys state]
  "Set the selected part based on the current keys."
  (let [parts (:parts state)
        primary-mode (:primary (:mode state))
        selected-part (get-selected-part-id (:parts state))]
    (if-let [part-num (first (->> keys
                                  (map js/parseInt)
                                  (filter (fn [k] (not (js/isNaN k))))))]
      (if (or (= :edit primary-mode) (contains? parts part-num))
        (-> state
            ((fn [s] (if selected-part
                       (assoc-in s [:parts selected-part :highlight] false)
                       s)))
            (assoc-in [:parts part-num :highlight] true))
        state)
      state)))

(defn handle-mode-keys [pressed-keys mode]
  "Set the primary mode based on the current keys."
  (let [primary (:primary mode)
        mode-map (get mode-keys primary)
        key (first (filter (fn [k] (contains? (set (keys mode-map)) k)) pressed-keys))]
    (if-let [next-mode (get mode-map key)]
      (assoc mode :primary next-mode :secondary nil)
      mode)))

(defn handle-secondary-mode-keys [pressed-keys mode]
  "Set the secondary mode based on the current keys."
  (let [primary-mode (:primary mode)
        current-secondary (:secondary mode)
        mode-map (get sub-mode-keys primary-mode)
        key (first (filter (fn [k] (contains? (set (keys mode-map)) k)) pressed-keys))]
    (assoc mode :secondary (if-let [next-mode (get mode-map key)]
                             next-mode
                             (or current-secondary (get mode-map :default))))))

(defn handle-edit-mode-keys [keys state]
  "Handle keypresses in edit mode."
  (if (= :edit (get-in state [:mode :primary]))
    (let [new-keys (set/difference (get-in state [:keyboard :pressed])
                                   (get-in state [:keyboard :previous-pressed]))
          new-keys (filter (fn [k] (js/isNaN (js/parseInt k))) new-keys)
          new-keys (filter (fn [k] (not (contains? #{\newline \return \formfeed} k))) new-keys)
          part-id (get-selected-part-id (:parts state))]
      (if part-id
        (-> state
            (update-in [:parts part-id :name]
                       (fn [name]
                         (reduce (fn [n c] (if (= "\b" c) (apply str (butlast n)) (str n c)))
                                 name new-keys))))
        state))
    state))

(defn handle-delete-mode-keys [_ state]
  "Handle keypresses in delete mode."
  (if (= :delete (get-in state [:mode :primary]))
    (let [new-keys (set/difference (get-in state [:keyboard :pressed])
                                   (get-in state [:keyboard :previous-pressed]))
          part-id (get-selected-part-id (:parts state))]
      (if (contains? new-keys "\b")
        (-> state
            (update-in [:parts] dissoc part-id)
            (update-in [:parts] (fn [parts] (if-let [key (first (keys parts))]
                                              (assoc-in parts [key :highlight] true)
                                              parts))))
        state))
    state))

(defn update-plan-annotations [waypoints plan]
  (let [loc->wp (into {} (map (fn [wp] [(:location wp) wp]) waypoints))]
    (map (partial get loc->wp) (map #(:location %) plan))))

(defn update-plan-animation [plan anim-state]
  (if (not (empty? plan))
    (let [last-point (:location (nth plan (:index anim-state)))
          next-point (:location (nth plan (+ 1 (:index anim-state))))
          current-offsets (:offsets anim-state)
          current-location (util/coord+ current-offsets last-point)
          next-offsets (util/coord+
                        current-offsets
                        (util/scale-to (util/coord- next-point last-point) animation-step-distance))
          next-location (util/coord+ next-offsets last-point)
          past-next (< (util/distance last-point next-point)
                       (util/distance last-point next-location))]
      (if (not past-next)
        (assoc anim-state :offsets next-offsets)
        (assoc anim-state
          :offsets util/origin
          :index (mod (+ 1 (:index anim-state)) (- (count plan) 1)))))
    default-animation-state))

(def pre-draw-transforms
  [
   [[:time] [:time] (fn [_ _] (current-time))]
   [[:keyboard :pressed] [] handle-edit-mode-keys]
   [[:keyboard :pressed] [] handle-delete-mode-keys]
   [[:keyboard :pressed] [:mode] handle-mode-keys]
   [[:keyboard :pressed] [:mode] handle-secondary-mode-keys]
   [[:keyboard :pressed] [] handle-part-keys]
   [[:mouse :location] [:ui :buttons] update-button-hover]
   [[:mouse] [:ui :buttons] update-button-click]
   [[:ui :buttons] [] handle-button-actions]
   [[] [:route] handle-waypoint-updates]
   [[:mouse :location] [:route :waypoints] highlight-waypoints]
   [[:route :waypoints] [:route :plan] update-plan-annotations]
   [[:route :plan] [:route :animation] update-plan-animation]
   ])

(def post-draw-transforms
  [
   [[:mouse :buttons] [:mouse :previous-buttons] copy]
   [[:keyboard :pressed] [:keyboard :previous-pressed] copy]
   ])

(defn on-state-change! []
  "Perform pre-draw transformations to application state."
  (swap! app-state apply-state-transforms pre-draw-transforms))

(defn post-draw []
  "Perform post-draw transformations to application state."
  (swap! app-state apply-state-transforms post-draw-transforms))

(defn on-event! []
  (on-state-change!)
  (post-draw))

(defn on-mouse-move! [event]
  "Handle mouse movement events."
  (.preventDefault event)
  (swap! app-state update-in [:mouse :location]
         (fn [m] (util/canvas->world (util/->canvas (.-clientX event) (.-clientY event)))))
  (on-event!))

(defn on-mouse-down! [event]
  "Handle mouse down events."
  (.preventDefault event)
  (swap! app-state update-in [:mouse :buttons (.-button event)] (constantly :down))
  (on-event!))

(defn on-mouse-up! [event]
  "Handle mouse up events."
  (.preventDefault event)
  (swap! app-state update-in [:mouse :buttons (.-button event)] (constantly :up))
  (on-event!))

(defn on-key-down! [event]
  "Handle key down events."
  (.preventDefault event)
  (swap! app-state update-in [:keyboard :pressed] conj (.fromCharCode js/String (.-keyCode event)))
  (on-event!))

(defn on-key-up! [event]
  "Handle key up events."
  (.preventDefault event)
  (swap! app-state update-in [:keyboard :pressed] disj (.fromCharCode js/String (.-keyCode event)))
  (on-event!))

(defn on-resize! [event]
  "Handle resize events."
  (.preventDefault event)
  (draw/size-canvas-to-window!)
  (on-event!))

(defn annotate-plan [waypoints plan]
  (let [loc->wp (into {} (map (fn [wp] [(:location wp) wp]) waypoints))]
    (map (partial get loc->wp) (map util/->world plan))))

(defn on-task-completion [app-state task]
  "Handle task completion events."
  (let [type (get task "type")
        result (get task "result")]
    (.log js/console type " task complete")
    (cond
     (= "plan" type) (-> app-state
                         (assoc-in [:route :plan]
                                   (annotate-plan (get-in app-state [:route :waypoints]) result))
                         (assoc-in [:route :animation] default-animation-state)
                         (assoc-in [:route :execution :current] 0))
     :else app-state)))

(defn update-task-state [app-state task]
  (let [state (get task "state")
        id (get task "id")]
    (if (= "complete" state)
      (-> app-state
          (on-task-completion task)
          (update-in [:tasks :pending] (fn [ids] (filterv (fn [x] (not (= id x))) ids)))
          (update-in [:tasks :complete] (fn [ids] (conj ids id))))
      app-state)))

(defn start-task! [task]
  "Send a task to the server and add its ID to the pending task list."
  (api/add-task! task
                 (fn [resp]
                   (.log js/console "Started task " (str resp) "id:" (get resp "id"))
                   (if (= "complete" (get resp "state"))
                     (swap! app-state update-task-state resp)
                     (swap! app-state update-in [:tasks :pending] #(conj % (get resp "id")))))
                 (fn [resp] (.log js/console "Failed to add task " (str task)))))

(defn clean-waypoint [waypoint]
  (let [{:keys [location kind part-id]} waypoint
        {:keys [x y]} location]
   {:x x :y y :z 0 :kind kind :part-id part-id}))

(defn plan-route! [waypoints]
  "Send a request to the server to plan a route using the current waypoints."
  (start-task! {:type :plan :waypoints (mapv clean-waypoint waypoints)}))

(defn check-heartbeat! []
  (let [now (current-time)
        latest (get-in @app-state [:connection :last-heartbeat])
        time-since-heartbeat (- now latest)]
    (when (< heartbeat-interval time-since-heartbeat)
      (api/get-meta (fn [_]
                      (swap! app-state assoc :connection {:last-heartbeat (current-time)
                                                          :connected true}))
                    (fn [_]
                      (swap! app-state update-in [:connection :connected] (constantly false)))))
    (when (< heartbeat-timeout time-since-heartbeat)
      (swap! app-state update-in [:connection :connected] (constantly false)))))

(defn check-tasks! []
  (let [now (current-time)
        latest (get-in @app-state [:tasks :last-poll])
        time-since-poll (- now latest)]
    (when (< task-update-interval time-since-poll)
      (doseq [id (get-in @app-state [:tasks :pending])]
        (api/get-task
         id
         (fn [resp]
           (swap! app-state update-task-state resp))
         (fn [err]
           (.log js/console "Error when checking on task state: " (str err))
           (swap! app-state update-in [:tasks :pending] #(filterv (fn [t] (not= id t)) %)))))
      (swap! app-state update-in [:tasks :last-poll] (constantly now)))))

(defn attach-handlers []
  (set! (.-onmousemove (util/get-canvas)) on-mouse-move!)
  (set! (.-onmouseup (util/get-canvas)) on-mouse-up!)
  (set! (.-onmousedown (util/get-canvas)) on-mouse-down!)
  (set! (.-onkeydown (util/get-body)) on-key-down!)
  (set! (.-onkeyup (util/get-body)) on-key-up!)
  (set! (.-onresize js/window) on-resize!))
