 (ns kixi.hecuba.security
  (:require
   [clojure.tools.logging :as log]
   [qbits.hayt :as hayt]
   [kixi.hecuba.storage.db :as db]
   [cemerick.friend :as friend]
   [clojure.edn :as edn]
   (cemerick.friend [workflows :as workflows]
                    [credentials :as creds])))

(derive ::user ::public)
(derive ::project-manager ::user)
(derive ::programme-manager ::project-manager)
(derive ::admin ::programme-manager)
(derive ::super-admin ::admin)

(defn get-user [store]
  (fn [username]
    (log/infof "Getting user: %s" username)
    (db/with-session [session (:hecuba-session store)]
      (let [user (first
                  (db/execute
                   session
                   (hayt/select :users
                                (hayt/where [[= :username username]]))))]
        (log/infof "Got user: %s" user)
        (merge {:username (:username user)
                :id       (:id user)
                :password (:password user)}
               (edn/read-string (:data user)))))))

(defn friend-middleware
  "Returns a middleware that enables authentication via Friend."
  [handler store]
  (let [friend-m {:credential-fn (partial creds/bcrypt-credential-fn (get-user store))
                  :default-landing-uri "/app"
                  :workflows
                  ;; Note that ordering matters here. Basic first.
                  [(workflows/http-basic :realm "/")
                   ;; The tutorial doesn't use this one, but you
                   ;; probably will.
                   (workflows/interactive-form :login-uri "/login")]}]
    (-> handler
        (friend/authenticate friend-m))))

(defn session-username [session]
  (-> session :cemerick.friend/identity :current))

(comment
  {:username "alice@example.com"
   :password "$2a$10$J9CJ8xfy1SrJxj0XwT5Eq.cKCKXAqR.4Cb81ikr6ilvNpszdWEVee"
   :roles #{::super-admin}
   :programmes #{}
   :projects #{}
   }
  (defn add-user [user store]
  (db/with-session [session (:hecuba-session store)]
    (hayt/insert :users
                 (hayt/values {:username (:username user)
                               :id (:username user)
                               :password (creds/hash-bcrypt (:password user))
                               :data (prn (dissoc user :username :password))})))))
