/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.impl.clone;

import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.Expression;
import net.hydromatic.linq4j.expressions.Primitive;
import net.hydromatic.optiq.*;

import org.eigenbase.reltype.RelRecordType;
import org.eigenbase.util.Pair;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Implementation of table that reads rows from column stores, one per column.
 * Column store formats are chosen based on the type and distribution of the
 * values in the column; see {@link Representation} and
 * {@link RepresentationType}.
 */
class ArrayTable<T>
    extends BaseQueryable<T>
    implements Table<T>
{
    private final Schema schema;
    private final List<Pair<Representation, Object>> pairs;
    private final int size;

    /** Creates an ArrayTable. */
    public ArrayTable(
        Schema schema,
        Type elementType,
        Expression expression,
        List<Pair<Representation, Object>> pairs,
        int size)
    {
        super(schema.getQueryProvider(), elementType, expression);
        this.schema = schema;
        this.pairs = pairs;
        this.size = size;

        assert ((RelRecordType) elementType).getRecordFields().size()
               == pairs.size();
    }

    public DataContext getDataContext() {
        return schema;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Enumerator<T> enumerator() {
        return new Enumerator() {
            final int rowCount = size;
            final int columnCount = pairs.size();
            int i = -1;

            public Object[] current() {
                Object[] objects = new Object[columnCount];
                for (int j = 0; j < objects.length; j++) {
                    final Pair<Representation, Object> pair = pairs.get(j);
                    objects[j] = pair.left.getObject(pair.right, i);
                }
                return objects;
            }

            public boolean moveNext() {
                return (++i < rowCount);
            }

            public void reset() {
                i = -1;
            }
        };
    }

    enum RepresentationType {
        /** Object array. Null values are represented by null. Values may or may
         * not be canonized; if canonized, = and != can be implemented using
         * pointer.
         *
         * @see ObjectArray
         */
        OBJECT_ARRAY,

        /**
         * Array of primitives. Null values not possible. Only for primitive
         * types (and not optimal for boolean).
         *
         * @see PrimitiveArray
         */
        PRIMITIVE_ARRAY,

        /** Bit-sliced primitive array. Values are {@code bitCount} bits each,
         * and interpreted as signed. Stored as an array of long values.
         *
         * <p>If gcd(bitCount, 64) != 0, some values will cross boundaries.
         * bits each. But for all of those values except 4, there is a primitive
         * type (8 byte, 16 short, 32 int) which is more efficient.
         *
         * @see BitSlicedPrimitiveArray
         */
        BIT_SLICED_PRIMITIVE_ARRAY,

        /**
         * Dictionary of primitives. Use one of the previous methods to store
         * unsigned offsets into the dictionary. Dictionary is canonized and
         * sorted, so v1 &lt; v2 if and only if code(v1) &lt; code(v2). The
         * dictionary may or may not contain a null value.
         *
         * @see PrimitiveDictionary
         */
        PRIMITIVE_DICTIONARY,

        /**
         * Dictionary of objects. Use one of the previous methods to store
         * unsigned offsets into the dictionary.
         *
         * @see ObjectDictionary
         */
        OBJECT_DICTIONARY,

        /**
         * Compressed string table. Block of char data. Strings represented
         * using an unsigned offset into the table (stored using one of the
         * previous methods).
         *
         * <p>First 2 bytes are unsigned length; subsequent bytes are string
         * contents. The null value, strings longer than 64k and strings that
         * occur very commonly are held in an 'exceptions' array and are
         * recognized by their high offsets. Other strings are created on demand
         * (this reduces the number of objects that need to be created during
         * deserialization from cache.</p>
         *
         * @see StringDictionary
         */
        STRING_DICTIONARY,

        /**
         * Compressed byte array table. Similar to compressed string table.
         *
         * @see ByteStringDictionary
         */
        BYTE_STRING_DICTIONARY,
    }

    public interface Representation {
        RepresentationType getType();

        <E> Object freeze(List<E> values);

        Object getObject(Object dataSet, int ordinal);
    }

    public static class ObjectArray implements Representation {
        final int ordinal;

        public ObjectArray(int ordinal) {
            this.ordinal = ordinal;
        }

        public RepresentationType getType() {
            return RepresentationType.OBJECT_ARRAY;
        }

        public <E> Object freeze(List<E> values) {
            // We assume:
            // 1. The array does not need to be copied.
            // 2. The values have been canonized.
            return values;
        }

        public Object getObject(Object dataSet, int ordinal) {
            return ((List) dataSet).get(ordinal);
        }
    }

    public static class PrimitiveArray implements Representation {
        final int ordinal;
        private final Primitive primitive;
        private final Primitive p;

        public PrimitiveArray(int ordinal, Primitive primitive, Primitive p) {
            this.ordinal = ordinal;
            this.primitive = primitive;
            this.p = p;
        }

        public RepresentationType getType() {
            return RepresentationType.PRIMITIVE_ARRAY;
        }

        public <E> Object freeze(final List<E> values) {
            return primitive.toArray2((List<Number>) values);
        }

        public Object getObject(Object dataSet, int ordinal) {
            switch (p) {
            case DOUBLE:
                return Array.getDouble(dataSet, ordinal);
            case FLOAT:
                return Array.getFloat(dataSet, ordinal);
            case BOOLEAN:
                return Array.getBoolean(dataSet, ordinal);
            case BYTE:
                return Array.getByte(dataSet, ordinal);
            case CHARACTER:
                return Array.getChar(dataSet, ordinal);
            case SHORT:
                return Array.getShort(dataSet, ordinal);
            case INT:
                return Array.getInt(dataSet, ordinal);
            case LONG:
                return Array.getLong(dataSet, ordinal);
            default:
                throw new AssertionError("unexpected " + p);
            }
        }
    }

    public static class PrimitiveDictionary implements Representation {
        public RepresentationType getType() {
            return RepresentationType.PRIMITIVE_DICTIONARY;
        }

        public <E> Object freeze(List<E> values) {
            throw new UnsupportedOperationException(); // TODO:
        }

        public Object getObject(Object dataSet, int ordinal) {
            throw new UnsupportedOperationException(); // TODO:
        }
    }

    public static class ObjectDictionary implements Representation {
        public RepresentationType getType() {
            return RepresentationType.OBJECT_DICTIONARY;
        }

        public <E> Object freeze(List<E> values) {
            throw new UnsupportedOperationException(); // TODO:
        }

        public Object getObject(Object dataSet, int ordinal) {
            throw new UnsupportedOperationException(); // TODO:
        }
    }

    public static class StringDictionary implements Representation {
        public RepresentationType getType() {
            return RepresentationType.STRING_DICTIONARY;
        }

        public <E> Object freeze(List<E> values) {
            throw new UnsupportedOperationException(); // TODO:
        }

        public Object getObject(Object dataSet, int ordinal) {
            throw new UnsupportedOperationException(); // TODO:
        }
    }

    public static class ByteStringDictionary implements Representation {
        public RepresentationType getType() {
            return RepresentationType.BYTE_STRING_DICTIONARY;
        }

        public <E> Object freeze(List<E> values) {
            throw new UnsupportedOperationException(); // TODO:
        }

        public Object getObject(Object dataSet, int ordinal) {
            throw new UnsupportedOperationException(); // TODO:
        }
    }

    public static class BitSlicedPrimitiveArray implements Representation {
        final int bitCount;
        private final Primitive primitive;
        private final int ordinal;

        BitSlicedPrimitiveArray(int ordinal, int bitCount, Primitive primitive)
        {
            this.ordinal = ordinal;
            this.bitCount = bitCount;
            this.primitive = primitive;
        }

        public RepresentationType getType() {
            return RepresentationType.BIT_SLICED_PRIMITIVE_ARRAY;
        }

        public <E> Object freeze(List<E> values) {
            final int chunksPerWord = 64 / bitCount;
            final int wordCount =
                (values.size() + (chunksPerWord - 1)) / chunksPerWord;
            final int remainingChunkCount = values.size() % chunksPerWord;
            final long[] longs = new long[wordCount];
            final int n = values.size() / chunksPerWord;
            int i;
            int k = 0;
            if (values.size() > 0
                && values.get(0) instanceof Boolean)
            {
                @SuppressWarnings("unchecked")
                final List<Boolean> booleans = (List<Boolean>) values;
                for (i = 0; i < n; i++) {
                    long v = 0;
                    for (int j = 0; j < chunksPerWord; j++) {
                        v |= (booleans.get(k++) ? (1 << (bitCount * j)) : 0);
                    }
                    longs[i] = v;
                }
                if (remainingChunkCount > 0) {
                    long v = 0;
                    for (int j = 0; j < remainingChunkCount; j++) {
                        v |= (booleans.get(k++) ? (1 << (bitCount * j)) : 0);
                    }
                    longs[i] = v;
                }
            } else {
                @SuppressWarnings("unchecked")
                final List<Number> numbers = (List<Number>) values;
                for (i = 0; i < n; i++) {
                    long v = 0;
                    for (int j = 0; j < chunksPerWord; j++) {
                        v |= (numbers.get(k++).longValue() << (bitCount * j));
                    }
                    longs[i] = v;
                }
                if (remainingChunkCount > 0) {
                    long v = 0;
                    for (int j = 0; j < remainingChunkCount; j++) {
                        v |= (numbers.get(k++).longValue() << (bitCount * j));
                    }
                    longs[i] = v;
                }
            }
            return longs;
        }

        public Object getObject(Object dataSet, int ordinal) {
            final long[] longs = (long[]) dataSet;
            final int chunksPerWord = 64 / bitCount;
            final int word = ordinal / chunksPerWord;
            final long v = longs[word];
            final int chunk = ordinal % chunksPerWord;
            final int mask = (1 << bitCount) - 1;
            final long x = (v >> (chunk * bitCount)) & mask;
            switch (primitive) {
            case BOOLEAN:
                return x != 0;
            case BYTE:
                return (byte) x;
            case CHARACTER:
                return (char) x;
            case SHORT:
                return (short) x;
            case INT:
                return (int) x;
            case LONG:
                return x;
            default:
                throw new AssertionError(primitive + " unexpected");
            }
        }

        public static long getLong(int bitCount, long[] values, int ordinal) {
            return getLong(
                bitCount, 64 / bitCount, (1L << bitCount) - 1L,
                values, ordinal);
        }

        public static long getLong(
            int bitCount,
            int chunksPerWord,
            long mask,
            long[] values,
            int ordinal)
        {
            int word = ordinal / chunksPerWord;
            int chunk = ordinal % chunksPerWord;
            long value = values[word];
            return (value >> (chunk * bitCount)) & mask;
        }

        public static void orLong(
            int bitCount, long[] values, int ordinal, long value)
        {
            orLong(bitCount, 64 / bitCount, values, ordinal, value);
        }

        public static void orLong(
            int bitCount, int chunksPerWord, long[] values, int ordinal,
            long value)
        {
            int word = ordinal / chunksPerWord;
            int chunk = ordinal % chunksPerWord;
            values[word] |= value << (chunk * bitCount);
        }
    }
}

// End ArrayTable.java