/*    */ package semmiedev.disc_jockey;
/*    */ 
/*    */ import java.io.EOFException;
/*    */ import java.io.IOException;
/*    */ import java.io.InputStream;
/*    */ import java.nio.ByteBuffer;
/*    */ import java.nio.ByteOrder;
/*    */ 
/*    */ public class BinaryReader {
/*    */   private final InputStream in;
/* 11 */   private final ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
/*    */   
/*    */   public BinaryReader(InputStream in) {
/* 14 */     this.in = in;
/*    */   }
/*    */   
/*    */   public int readInt() throws IOException {
/* 18 */     return this.buffer.clear().put(readBytes(4)).rewind().getInt();
/*    */   }
/*    */   
/*    */   public long readUInt() throws IOException {
/* 22 */     return readInt() & 0xFFFFFFFFL;
/*    */   }
/*    */   
/*    */   public int readUShort() throws IOException {
/* 26 */     return readShort() & 0xFFFF;
/*    */   }
/*    */   
/*    */   public short readShort() throws IOException {
/* 30 */     return this.buffer.clear().put(readBytes(2)).rewind().getShort();
/*    */   }
/*    */   
/*    */   public String readString() throws IOException {
/* 34 */     return new String(readBytes(readInt()));
/*    */   }
/*    */   
/*    */   public float readFloat() throws IOException {
/* 38 */     return this.buffer.clear().put(readBytes(4)).rewind().getFloat();
/*    */   }
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */   
/*    */   public byte readByte() throws IOException {
/* 57 */     int b = this.in.read();
/* 58 */     if (b < 0) throw new EOFException(); 
/* 59 */     return (byte)b;
/*    */   }
/*    */   
/*    */   public byte[] readBytes(int length) throws IOException {
/* 63 */     byte[] bytes = new byte[length];
/* 64 */     for (int i = 0; i < length; ) { bytes[i] = readByte(); i++; }
/* 65 */      return bytes;
/*    */   }
/*    */ }


/* Location:              D:\Minecraft\Minecraft Java\jars\Disc_Jockey_Plus 测试版\2.x\2.0.1\可用文件\DJP-Fabric-2.0.1-rc-0.0.13+mc26.2.jar!\semmiedev\disc_jockey\BinaryReader.class
 * Java compiler version: 25 (69.0)
 * JD-Core Version:       1.1.3
 */