(ns blak3mill3r.coffi-infer
  (:require [coffi.mem :as mem :refer [defalias]]
            [coffi.ffi :as ffi :refer [defcfn]]))

(ffi/load-system-library "clang")

(defcfn create-index
  "Provides a shared context for creating translation units."
  clang_createIndex
  [::mem/int
   ::mem/int]
  ::mem/pointer)

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

(defalias ::cursor
  [::mem/struct
   [[:kind ::mem/int]
    [:xdata ::mem/int]
    [:data [::mem/array ::mem/pointer 3]]]])

(defalias ::cursor-visitor
  [::ffi/fn [::cursor       ;; cursor
             ::cursor       ;; parent cursor
             ::mem/pointer] ;; client data
   ::mem/int])

(defcfn get-translation-unit-cursor
  "Retrieve the cursor that represents the given translation unit.
  The translation unit cursor can be used to start traversing the various declarations within the given translation unit."
  clang_getTranslationUnitCursor
  [::mem/pointer]
  ::cursor)

(defcfn get-cursor-kind
  "Retrieve the kind of the given cursor."
  clang_getCursorKind
  [::mem/pointer]
  ;; the actual size of the enum type is potentially compiler-dependent, I believe 2 bytes is correct for CursorKind but I should check
  ::mem/short)

(def my-index (delay (create-index 0 0)))

(def my-tu
  (delay
    (create-translation-unit-from-source-file
     @my-index "include/try.hpp"
     0 jdk.incubator.foreign.MemoryAddress/NULL
     0 jdk.incubator.foreign.MemoryAddress/NULL) ))

(def my-tu-cursor
  (delay
    (get-translation-unit-cursor @my-tu)))

(defcfn visit-children
  "Visit the children of a particular cursor.
  This function visits all the direct children of the given cursor, invoking the given visitor function with the cursors of each visited child. The traversal may be recursive, if the visitor returns CXChildVisit_Recurse. The traversal may also be ended prematurely, if the visitor returns CXChildVisit_Break."
  clang_visitChildren
  [::cursor
   ::cursor-visitor
   ::mem/pointer]
  ::mem/int)

(def well-now (atom nil))

(defn my-visitor [cursor parent user-data]
  (reset! well-now :true)
  (int 2))

(defn do-it []
  (visit-children
   @my-tu-cursor
   my-visitor
   jdk.incubator.foreign.MemoryAddress/NULL
   ))

(comment
  (do-it)

  (def main-class-loader @clojure.lang.Compiler/LOADER)

  (defn my-visitor [cursor parent user-data]
    (.setContextClassLoader (Thread/currentThread) main-class-loader)
    (println "Something: " (type cursor))
    2)



  

  )
