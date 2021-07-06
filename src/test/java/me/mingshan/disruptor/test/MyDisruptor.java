package me.mingshan.disruptor.test;

import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ThreadFactory;

public class MyDisruptor {
  public static void main(String[] args) throws InterruptedException {
    test();
  }

  private static void test() throws InterruptedException {
    Disruptor<Message> disruptor = startDisruptor();
    RingBuffer<Message> ringBuffer = disruptor.getRingBuffer();

    for (int i = 0; i < 20; i++) {
      // 获取下一个可用位置的下标
      long sequence = ringBuffer.next();
      try {
        // 返回可用位置的元素
        Message event = ringBuffer.get(sequence);
        // 设置该位置元素的值
        event.setId("" + i);
      } finally {
        ringBuffer.publish(sequence);
        System.out.println("publish，" + sequence);
      }
      Thread.sleep(10);
    }

    disruptor.shutdown();
  }

  public static Disruptor<Message> startDisruptor() throws InterruptedException {
    EventFactory<Message> eventFactory = new EventFactory<Message>() {
      @Override
      public Message newInstance() {
        return new Message();
      }
    };

    int bufferSize = 4;

    // 生产者的线程工厂
    ThreadFactory threadFactory = new ThreadFactory() {
      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "simpleThread");
      }
    };

    Disruptor<Message> disruptor = new Disruptor<>(eventFactory,
        bufferSize,
        threadFactory,
        ProducerType.SINGLE,
        new SleepingWaitStrategy());

    disruptor.handleEventsWith(new EventHandler<Message>() {
      @Override
      public void onEvent(Message event, long sequence, boolean endOfBatch) throws Exception {
        System.out.println("consume，" + sequence);
        System.out.println(event);
        System.out.println(endOfBatch);
      }
    });
    disruptor.start();


    return disruptor;
  }

}
