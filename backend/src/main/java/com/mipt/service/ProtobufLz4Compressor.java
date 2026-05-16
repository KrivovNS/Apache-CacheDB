package com.mipt.service;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import com.mipt.model.DataType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

public class ProtobufLz4Compressor {

  private static final int SCHEMA_VERSION = 1;
  private static final int CODEC_LZ4 = 1;

  private final LZ4Compressor compressor;
  private final LZ4FastDecompressor decompressor;

  public ProtobufLz4Compressor() {
    LZ4Factory lz4Factory = LZ4Factory.fastestInstance();
    this.compressor = lz4Factory.fastCompressor();
    this.decompressor = lz4Factory.fastDecompressor();
  }

  public byte[] encode(Object data, DataType dataType) {
    Objects.requireNonNull(data, "Data cannot be null");
    Objects.requireNonNull(dataType, "DataType cannot be null");

    byte[] rawPayload = toRawPayload(data, dataType);
    byte[] compressed = compress(rawPayload);
    String typeName = dataType.name();

    int size = CodedOutputStream.computeInt32Size(1, SCHEMA_VERSION)
        + CodedOutputStream.computeStringSize(2, typeName)
        + CodedOutputStream.computeInt32Size(3, CODEC_LZ4)
        + CodedOutputStream.computeInt32Size(4, rawPayload.length)
        + CodedOutputStream.computeBytesSize(5, ByteString.copyFrom(compressed));

    byte[] protobufEnvelope = new byte[size];

    try {
      CodedOutputStream output = CodedOutputStream.newInstance(protobufEnvelope);
      output.writeInt32(1, SCHEMA_VERSION);
      output.writeString(2, typeName);
      output.writeInt32(3, CODEC_LZ4);
      output.writeInt32(4, rawPayload.length);
      output.writeBytes(5, ByteString.copyFrom(compressed));
      output.flush();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to build protobuf envelope", e);
    }

    return protobufEnvelope;
  }

  public Object decode(byte[] encodedPayload, DataType expectedDataType) {
    Objects.requireNonNull(encodedPayload, "Encoded payload cannot be null");
    Objects.requireNonNull(expectedDataType, "Expected DataType cannot be null");

    int schemaVersion = -1;
    String storedDataType = null;
    int codec = -1;
    int originalSize = -1;
    byte[] compressedPayload = null;

    try {
      CodedInputStream input = CodedInputStream.newInstance(encodedPayload);
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 0) {
          break;
        }

        int fieldNumber = WireFormat.getTagFieldNumber(tag);
        switch (fieldNumber) {
          case 1 -> schemaVersion = input.readInt32();
          case 2 -> storedDataType = input.readString();
          case 3 -> codec = input.readInt32();
          case 4 -> originalSize = input.readInt32();
          case 5 -> compressedPayload = input.readByteArray();
          default -> input.skipField(tag);
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid protobuf payload", e);
    }

    validateEnvelope(schemaVersion, storedDataType, codec, originalSize, compressedPayload,
        expectedDataType);

    byte[] decompressed = new byte[originalSize];
    try {
      decompressor.decompress(compressedPayload, 0, decompressed, 0, originalSize);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("LZ4 decompression failed", e);
    }

    return fromRawPayload(decompressed, expectedDataType);
  }

  private void validateEnvelope(int schemaVersion, String storedDataType, int codec,
      int originalSize, byte[] compressedPayload, DataType expectedDataType) {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported schema version: " + schemaVersion);
    }

    if (codec != CODEC_LZ4) {
      throw new IllegalArgumentException("Unsupported codec id: " + codec);
    }

    if (originalSize < 0) {
      throw new IllegalArgumentException("Invalid original payload size: " + originalSize);
    }

    if (compressedPayload == null || compressedPayload.length == 0) {
      throw new IllegalArgumentException("Compressed payload is empty");
    }

    if (!expectedDataType.name().equals(storedDataType)) {
      throw new IllegalArgumentException(
          "Stored data type mismatch. expected=" + expectedDataType.name()
              + ", actual=" + storedDataType
      );
    }
  }

  private byte[] compress(byte[] rawPayload) {
    int maxCompressedLength = compressor.maxCompressedLength(rawPayload.length);
    byte[] buffer = new byte[maxCompressedLength];
    int compressedLength = compressor.compress(rawPayload, 0, rawPayload.length, buffer, 0,
        maxCompressedLength);
    return Arrays.copyOf(buffer, compressedLength);
  }

  private byte[] toRawPayload(Object data, DataType dataType) {
    return switch (dataType) {
      case JSON, STRING -> data.toString().getBytes(StandardCharsets.UTF_8);
      case BYTES -> {
        if (!(data instanceof byte[] bytes)) {
          throw new IllegalArgumentException("BYTES type requires byte[] payload");
        }
        yield bytes;
      }
    };
  }

  private Object fromRawPayload(byte[] payload, DataType dataType) {
    return switch (dataType) {
      case JSON, STRING -> new String(payload, StandardCharsets.UTF_8);
      case BYTES -> payload;
    };
  }
}
