/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

import com.lmax.disruptor.util.Util;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    long nextValue = Sequence.INITIAL_VALUE;
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.</p>
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * 核心方法，生产者获取下一个可用的序号。
     *
     * @see Sequencer#next(int)
     */
    @Override
    public long next(int n)
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        // 当前的序号，初始值为-1
        long nextValue = this.nextValue;

        // 将要获取的序号
        long nextSequence = nextValue + n;
        // wrapPoint是一个临界序号，必须比当前最小的未消费序号还小
        long wrapPoint = nextSequence - bufferSize;
        // 当前所有消费者最小的序号， 默认为-1，缓存值，上一次计算最小序号的结果
        long cachedGatingSequence = this.cachedValue;


        /*
         * 核心逻辑：
         *
         * 生产者获取的可用序号必须小于所有消费者的序号最小的值。这样保证生产者不会将消费者未消费的数据覆盖。
         *
         * 如果生产者获取的可用序号大于等于所有消费者的序号最值的值，代表发生了冲突，生产者必须等待消费者消费完后再继续生产。
         *
         */

        // wrapPoint > cachedGatingSequence
        // 如果wrapPoint比当前的cachedValue还大，如果上次消费者还未消费成功，可能再次发生冲突
        // cachedGatingSequence > nextValue
        // 只会在https://github.com/LMAX-Exchange/disruptor/issues/76情况下存在，跑测试用例复现不出来。。
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // 这个是什么意思？
            // https://github.com/LMAX-Exchange/disruptor/issues/291
            cursor.setVolatile(nextValue);  // StoreLoad fence

            // 获取消费者的最小序号，因为生产者获取的下一个可用序号不能超过该最小序号，否则会造成数据被覆盖
            long minSequence;
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                // 生产者一直自旋
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            this.cachedValue = minSequence;
        }

        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(long sequence)
    {
        // 游标设置为当前的sequence
        // 这里是什么意思呢？
        // https://github.com/LMAX-Exchange/disruptor/issues/265

        /**
         * A store/store barrier is sufficient in this case as all we need to guarantee is that the message data is stored to "memory"* before the update to the sequence value.
         * A setVolatile is only required if we are reading and writing concurrent data on the same thread, in our case we are only writing.
         *
         * On the reading thread we only need a load/load barrier to ensure that the message data is read from "memory" after the sequence is read.
         * So in the situation where the reading thread sees the sequence value increment the message data will be visible as the
         * data ordering is transitive. I.e. if storing the message data happens before updating the sequence and updating the sequence happens
         * before the new sequence is read and reading the message data happens after the sequence is read, we can infer that writing the message data
         * happens before the message data is read.
         *
         * "memory" in this instance is the abstract notion of memory, not physical ram.
         */

        cursor.set(sequence);
        // 通知消费者去消费数据
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
