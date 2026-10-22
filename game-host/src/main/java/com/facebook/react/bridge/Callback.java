package com.facebook.react.bridge;
/** Exact callback contract expected by this developer engine; not the React Native API. */
public final class Callback {
 public final java.util.function.BiConsumer<Integer,String> completed;
 public Callback(java.util.function.BiConsumer<Integer,String> completed){this.completed=completed;}
 public void Result(int code,String result){completed.accept(code,result);}
 public void Reject(int code){completed.accept(code,"{\"error\":\"rejected\"}");}
}
