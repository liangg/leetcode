package com.css.kitchen.service;

import com.css.kitchen.Kitchen;
import com.css.kitchen.common.Order;
import com.css.kitchen.util.MetricsManager;
import com.css.kitchen.util.OrderReader;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A scheduled service that simulates food order submission from customers. It reads orders
 * from a food orders json file, and submits them to the central kitchen for processing.
 */
@NoArgsConstructor
public class OrderSource extends CssScheduler {
  private static Logger logger = LoggerFactory.getLogger(OrderSource.class);

  // a rough estimated poisson process probability of order every 100ms
  static final double orderRateLambda = 0.325;

  private Kitchen kitchen;
  private Lock lock = new ReentrantLock();
  private volatile boolean ordersExhausted = false; // used for app termination
  private List<Order> orders = Collections.emptyList();
  @Getter private int lastPosition = 0;

  public OrderSource(Kitchen kitchen) {
    this.kitchen = kitchen;
  }

  @Override
  public String name() { return "OrderSource"; }

  public void start(String orderJsonFile) {
    // read orders from json to list for simulated order receiving
    this.orders = OrderReader.readOrdersJson(orderJsonFile);

    // submit orders in simulated poisson distribution rate
    Runnable task = () -> {
      if (lastPosition < orders.size()) {
        if (orderArrived()) {
          Order order = orders.get(lastPosition++);
          logger.debug("submit order " + order);
          kitchen.submitOrder(order);
          MetricsManager.incr(MetricsManager.SUBMITTED_ORDERS);
        }
        return;
      }

      // we have submitted our orders, notify kitchen to close the shop
      try {
        if (lock.tryLock(500, TimeUnit.MILLISECONDS)) {
          if (!ordersExhausted) {
            ordersExhausted = true;
          }
        }
      } catch (InterruptedException ex) {
        logger.error("order source task failed to acquire lock");
        ex.printStackTrace();
      } finally {
        lock.unlock();
      }
    };

    logger.info("OrderSource schedules task");
    executor.scheduleAtFixedRate(task, 50, 100, TimeUnit.MILLISECONDS);
  }

  public boolean hasOrder() {
    boolean result = true;
    try {
      if (lock.tryLock(200, TimeUnit.MILLISECONDS)) {
        result = ordersExhausted;
      }
    } catch (InterruptedException ex) {
      logger.error("order source check failed to acquire lock");
      ex.printStackTrace();
    } finally {
      lock.unlock();
    }
    return !result;
  }

  // simulated order rate with poisson process at a rate 3.25 every second
  private boolean orderArrived() {
    return Double.compare(Math.random(), orderRateLambda) < 0;
  }
}
