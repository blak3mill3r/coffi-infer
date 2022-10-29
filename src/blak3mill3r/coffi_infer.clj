(ns blak3mill3r.coffi-infer
  (:require [coffi.mem :as mem :refer [defalias]]
            [coffi.ffi :as ffi :refer [defcfn]]
            [coffi.layout :as layout]
            [clojure.stacktrace :as st]
            [clojure.set :as set]))

(ffi/load-system-library "clang")

(def cursor-kind-enum
  {2 ::struct-decl
   4 ::class-decl
   5 ::enum-decl
   6 ::field-decl
   9 ::var-decl
   8 ::function-decl
   300 ::translation-unit})

(defcfn create-index
  "Provides a shared context for creating translation units."
  clang_createIndex
  [::mem/int
   ::mem/int]
  ::mem/pointer)

(defcfn dispose-index
  "Clean up (must dispose all TUs within this index prior to disposing the index)"
  clang_disposeIndex
  [::mem/pointer]
  ::mem/void)

(defrecord Index
    [native]
  java.io.Closeable
  (close [this]
    (dispose-index native)))

(defn clang-index
  "Construct and wrap a clang Index"
  [a b]                                 ;; FIXME what are these flags?
  (->Index (create-index a b)))

(defcfn create-translation-unit-from-source-file
  "Return the CXTranslationUnit for a given source file and the provided command line arguments one would pass to the compiler."
  clang_createTranslationUnitFromSourceFile
  [::mem/pointer  ;; index
   ::mem/c-string ;; source file name
   ::mem/int      ;; argument count
   ::mem/pointer  ;; pointer to array of c-strings for cli options
   ::mem/int      ;; this is an `unsigned`, the count of unsaved files
   ::mem/pointer] ;; a pointer to the unsaved file structs
  ::mem/pointer)

(defcfn dispose-translation-unit
  "Clean up"
  clang_disposeTranslationUnit
  [::mem/pointer]
  ::mem/void)

(defrecord TranslationUnit [native]
  java.io.Closeable
  (close [this]
    (dispose-translation-unit native)))

(defn tu-from-source-file
  [index filename]
  (->TranslationUnit
   (create-translation-unit-from-source-file
    index filename
    ;;2 (mem/address-of (mem/serialize ["-mthread-model" "single"] [::mem/array ::mem/c-string 2]))
    0 jdk.incubator.foreign.MemoryAddress/NULL
    0 jdk.incubator.foreign.MemoryAddress/NULL)))

(defalias ::cursor
  (layout/with-c-layout
    [::mem/struct
     [[:kind ::mem/int]
      [:xdata ::mem/int]
      [:data [::mem/array ::mem/pointer 3]]]]))

(defalias ::source-location
  (layout/with-c-layout
    [::mem/struct
     [[:ptr-data [::mem/array ::mem/pointer 2]]
      [:int-data ::mem/int]]]))

(defalias ::cursor-visitor
  [::ffi/fn [::cursor       ;; cursor
             ::cursor       ;; parent cursor
             ::mem/pointer] ;; client data
   ::mem/int])

(defalias ::cxstring-raw
  (layout/with-c-layout
    [::mem/struct
     [[:data ::mem/pointer]
      [:private-flags ::mem/int]]]))

(def cxstring-layout
  (mem/c-layout
   ::cxstring-raw))

(defmethod mem/c-layout
  ::cxstring
  [_type]
  cxstring-layout)

#_(defmethod mem/serialize-into
  ::cxstring
  )

;; NOTE:(Blake) Joshua pointed out that it's silly to have clang_ prefix on all of these

(def get-c-string
  (ffi/make-downcall 'clang_getCString [::cxstring-raw] ::mem/c-string))

(def dispose-string
  (ffi/make-downcall 'clang_disposeString [::cxstring-raw] ::mem/void))

(defmethod mem/deserialize-from
  ::cxstring
  [segment _type]
  (try
    (mem/deserialize* (get-c-string segment) ::mem/c-string)
    (finally
      (dispose-string segment))))

(defcfn get-translation-unit-cursor
  "Retrieve the cursor that represents the given translation unit.
  The translation unit cursor can be used to start traversing the various declarations within the given translation unit."
  clang_getTranslationUnitCursor
  [::mem/pointer]
  ::cursor)

(defcfn cursor-location
  "The source location of a cursor"
  clang_getCursorLocation
  [::cursor]
  ::source-location)

(defcfn get-cursor-kind
  "Retrieve the kind of the given cursor."
  clang_getCursorKind
  [::mem/pointer]
  ;; the actual size of the enum type is potentially compiler-dependent, I believe 2 bytes is correct for CursorKind but I should check
  ::mem/short)

(defmacro with-index [[symbol] & body]
  `(let [~symbol (create-index 1 1)]
     ~@body
     (dispose-index ~symbol)))

(defmacro with-tu-from-file [index filename [symbol] & body]
  `(let [~symbol
         (create-translation-unit-from-source-file
          ~index ~filename
        ;;2 (mem/address-of (mem/serialize ["-mthread-model" "single"] [::mem/array ::mem/c-string 2]))
          0 jdk.incubator.foreign.MemoryAddress/NULL
          0 jdk.incubator.foreign.MemoryAddress/NULL)]
     ~@body
     (dispose-translation-unit ~symbol)))

(defcfn visit-children
  "Visit the children of a particular cursor.
  This function visits all the direct children of the given cursor, invoking the given visitor function with the cursors of each visited child. The traversal may be recursive, if the visitor returns CXChildVisit_Recurse. The traversal may also be ended prematurely, if the visitor returns CXChildVisit_Break."
  clang_visitChildren
  [::cursor
   ::cursor-visitor
   ::mem/pointer]
  ::mem/int)

(defcfn cursor-spelling
  "Returns the spelling of the cursor"
  clang_getCursorSpelling
  [::cursor]
  ::cxstring)

(defcfn location-is-from-main-file?
  "Is the location from the main file of the translation unit?"
  clang_Location_isFromMainFile
  [::source-location]
  ::mem/int
  ;; symbol to represent the native c function (in case we wanted to recur)
  location-is-from-main-file-native-fn
  ;; arglist of the clojure var we are defining
  [source-location]
  (not (zero? (location-is-from-main-file-native-fn source-location))))

(defn my-visitor [cursor parent user-data]
  (try
    (if (-> cursor cursor-location location-is-from-main-file?)
      (do (println (cursor-kind-enum (:kind cursor) (:kind cursor)))
          (println (cursor-spelling cursor))
          (case (cursor-kind-enum (:kind cursor))
            ::struct-decl
            (int 2)
            (int 1)))
      (int 1))
    (catch Exception e
      (println "Caught, ignore: " e)
      (st/print-stack-trace e)
      (int 0))))

(defn go []
  (with-open [idx (clang-index 1 1)]
    (with-open [tu (tu-from-source-file (:native idx) "include/try.hpp")]
      (visit-children (get-translation-unit-cursor (:native tu))
                      my-visitor
                      jdk.incubator.foreign.MemoryAddress/NULL))))

(comment
  #_(def main-class-loader @clojure.lang.Compiler/LOADER)
  (go)


  
  )
