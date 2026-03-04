package com.agent.common.utils.generator;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class RandomSupplier {
    private RandomSupplier() {}

    /**
     * Returns the platform default for random number generation.
     *
     * <p>The underlying implementation attempts to use {@link java.util.concurrent.ThreadLocalRandom}
     * on platforms where this is the most efficient.
    //  */
    public static Supplier<Random> platformDefault() {
      if ("Dalvik".equals(System.getProperty("java.vm.name"))) {
        return AndroidFriendlyRandomHolder.INSTANCE;
      }
      return ThreadLocalRandom::current;
    }
}
