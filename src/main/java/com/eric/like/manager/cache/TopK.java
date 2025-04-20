package com.eric.like.manager.cache;// TopK.java
import java.util.List;
import java.util.concurrent.BlockingQueue;

public interface TopK {
    AddResult add(String key, int increment);
    List<Item> list();
    BlockingQueue<Item> expelled();
    void fading();
    long total();
}