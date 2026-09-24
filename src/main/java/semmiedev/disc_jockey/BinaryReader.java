package semmiedev.disc_jockey;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BinaryReader {
  private final InputStream in;
  private final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
  
  public BinaryReader(InputStream in) {
    this.in = in;
  }
  
  public int readInt() throws IOException {
    return this.buffer.clear().put(readBytes(4)).rewind().getInt();
  }
  
  public long readUInt() throws IOException {
    return readInt() & 0xFFFFFFFFL;
  }
  
  public int readUShort() throws IOException {
    return readShort() & 0xFFFF;
  }
  
  public short readShort() throws IOException {
    return this.buffer.clear().put(readBytes(2)).rewind().getShort();
  }
  
  public String readString() throws IOException {
    return new String(readBytes(readInt()));
  }
  
  public float readFloat() throws IOException {
    return this.buffer.clear().put(readBytes(4)).rewind().getFloat();
  }















  
  public byte readByte() throws IOException {
     int b = this.in.read();
     if (b < 0) throw new EOFException(); 
     return (byte)b;
  }
  
  public byte[] readBytes(int length) throws IOException {
     byte[] bytes = new byte[length];
     for (int i = 0; i < length; ) { bytes[i] = readByte(); i++; }
      return bytes;
  }
}


