package com.agent.common.utils.generator;

import java.util.Random;
import java.util.function.Supplier;

enum AndroidFriendlyRandomHolder implements Supplier<Random> {
  INSTANCE;

  private static final Random random = new Random();

  @Override
  public Random get() {
    return random;
  }
}
