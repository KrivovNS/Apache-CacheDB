package com.mipt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mipt.service.ProtobufLz4Compressor;
import com.mipt.model.DataType;
import org.junit.jupiter.api.Test;

class ProtobufLz4CompressorTest {

  private final ProtobufLz4Compressor codec = new ProtobufLz4Compressor();

  @Test
  void shouldRoundTripString() {
    String source = "cache-value-123";
    byte[] encoded = codec.encode(source, DataType.STRING);
    Object decoded = codec.decode(encoded, DataType.STRING);
    assertEquals(source, decoded);
  }

  @Test
  void shouldRoundTripJson() {
    String source = "{\"name\":\"cache\",\"v\":1}";
    byte[] encoded = codec.encode(source, DataType.JSON);
    Object decoded = codec.decode(encoded, DataType.JSON);
    assertEquals(source, decoded);
  }

  @Test
  void shouldRoundTripBytes() {
    byte[] source = new byte[] {1, 2, 3, 4, 5, 127, -1};
    byte[] encoded = codec.encode(source, DataType.BYTES);
    Object decoded = codec.decode(encoded, DataType.BYTES);
    assertArrayEquals(source, (byte[]) decoded);
  }

  @Test
  void shouldFailOnDataTypeMismatch() {
    byte[] encoded = codec.encode("abc", DataType.STRING);
    assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded, DataType.JSON));
  }
}
