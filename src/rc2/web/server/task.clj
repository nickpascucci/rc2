(ns rc2.web.server.task
  (:require [schema.core :as s]
            [clojure.core.async :refer [go <! >! >!! chan close! timeout]]))

;;;; Task management facilities for RC2 API server.
(defonce model (ref {:events {}
                     :last-event-id 0
                     :tasks {}
                     :last-task-id 0
                     :device-status {}}))
(defonce task-types (atom {}))

(def initialized (atom false))
(def execution-state (atom :running)) ;; Can be :running or :paused
(defonce queues {:dispatch (chan 500)
                 :serial (chan 500)
                 :parallel (chan 50)
                 :high-priority (chan 50)})

(def Task {(s/required-key :id) s/Num
           (s/required-key :type) s/Keyword
           (s/required-key :created) s/Num
           (s/required-key :update) s/Num
           (s/required-key :state) s/Keyword
           :affinity s/Keyword
           :waypoints [{(s/required-key :x) s/Num
                        (s/required-key :y) s/Num
                        (s/required-key :z) s/Num}]})

(declare update-task!)

(defn register-task-type! [type handler &{:keys [affinity] :or {affinity :serial}}]
  "Register a handler for tasks of the given 'type.

  Handler functions take a task as an argument, and the returned value is recorded as the result of
  the task. Exceptions in handlers will cause the task to fail. If not specified, the task affinity
  is defaulted to serial."
  (swap! task-types assoc type {:handler handler
                                :affinity affinity})
  (println "Registered task type" type))

;; This function is intended to be used only within this module. It is public only for unit testing.
(defn do-task! [task handlers]
  "Performs a task and returns the state updates which should be applied to it. If respect-pause is
  false, continue processing tasks even if the system is paused."
  (if-let [handler (get handlers (:type task))]
    (try
      [:state :complete :result (handler task)]
      (catch Exception e
        [:state :failed :errors [(.getMessage e)]]))
    [:state :failed :errors [(str "No handler for task type " (:type task))]]))

(defn- dispatch-queues! [queues]
  "Allocate tasks from the 'dispatch queue into the queue matching the task affinity."
  (go (loop [task (<! (:dispatch queues))]
        (when task
          (>! (get queues (:affinity task))
              task)
          (recur (<! (:dispatch queues)))))))

(defn get-handlers [types]
  "Extract handler functions into a map keyed by type from the full task data map."
  (into {} (map (fn [[type data]] [type (:handler data)]) types)))

(defn- process-queue! [queue respect-pause]
  "Process tasks from the queue until it is closed."
  (go (loop [task (<! queue)]
        (when task
          (update-task! (:id task) :state :processing)
          (apply (partial update-task! (:id task))
                 (do (while (and (= :paused @execution-state) respect-pause)
                       (<! (timeout 100))) ;; Check every 100ms for unpause.
                     (do-task! task (get-handlers @task-types))))
          (recur (<! queue))))))

(defn init-workers! [num-parallel]
  "Create worker jobs to consume tasks. Creates one worker per serial queue and 'num-parallel
parallel ones. This function does not check to see if workers have already been created."
  (if @initialized
    false
    (do (dispatch-queues! queues)
        (process-queue! (:high-priority queues) false)
        (process-queue! (:serial queues) true)
        (dotimes [i num-parallel]
          (process-queue! (:parallel queues) true))
        (reset! initialized true))))

(defn shutdown-queues! [queues]
  "Shut down the queues so that workers will terminate when the last task is consumed."
  (map close! (vals queues)))

(defn dispatch-task! [task]
  "Add a task to the dispatch queue for execution."
  (>!! (:dispatch queues) task)
  task)

(defn add-event! [event]
  "Add a new event to the log."
  (dosync
   (let [event-id (inc (:last-event-id @model))]
     (alter model assoc-in [:events event-id] event)
     (alter model assoc :last-event-id event-id)
     event-id)))

(defn- current-time []
  "Get the current time in milliseconds."
  (System/currentTimeMillis))

(defn- make-event [task-id errors & deltas]
  {:pre [(even? (count deltas))]}
  "Create a new event."
  {:created (current-time)
   :errors errors
   :task task-id
   :changed (apply hash-map deltas)})

(defn update-task! [task-id & deltas]
  "Update a task by field. Example: (update-task 1 :status :started)"
  (dosync
   (let [event (apply (partial make-event task-id nil) deltas)
         event-id (add-event! event)
         original-task (assoc (get-in @model [:tasks task-id]) :update event-id)
         updated-task (merge original-task (apply hash-map deltas))]
     (alter model assoc-in [:tasks task-id] updated-task)
     original-task)))

(defn make-task [type options]
  "Create a new task."
  (if-let [type-data (get @task-types type)]
    (let [template {:created (current-time) :type type}
          task (merge options template)]
      ;; TODO This should probably also perform type-specific validation
      (assoc task :affinity (:affinity type-data)))
    (throw (Exception. "Unrecognized task type " type))))

(defn add-task! [type options]
  "Add a task to the task listing."
  (dosync
   (let [task-id (inc (:last-task-id @model))
         task (assoc (make-task type options) :id task-id)]
     (alter model assoc-in [:tasks task-id] task)
     (alter model assoc :last-task-id task-id)
     (dispatch-task! (update-task! task-id :state :new)))))

(defn cancel-task! [id]
  "Cancel a task by ID."
  (update-task! id :state :cancelled))

(defn pause-task-execution! []
  "Temporarily pause execution of new tasks."
  (println "Pausing task execution.")
  (reset! execution-state :paused))

(defn resume-task-execution! []
  "Un-pause execution of new tasks."
  (println "Resuming task execution.")
  (reset! execution-state :running))

(defn get-tasks []
  "Get the task registry."
  (:tasks @model))

(defn get-task [id]
  "Get a specific task from the task registry by id."
  (get (get-tasks) id))

(defn get-events []
  "Get the event registry."
  (:events @model))

(defn get-event [id]
  "Get a specific event from the event registry by id."
  (get (get-events) id))
