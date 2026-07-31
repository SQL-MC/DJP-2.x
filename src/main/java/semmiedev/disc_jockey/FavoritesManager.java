/*    */ package semmiedev.disc_jockey;
/*    */ 
/*    */ import java.util.ArrayList;
/*    */ import java.util.List;
/*    */ 
/*    */ public class FavoritesManager {
/*  7 */   private static final List<String> FAV = new ArrayList<>();
/*    */   
/*  9 */   public static boolean isFavorite(String n) { return FAV.contains(n.trim().toLowerCase()); }
/* 10 */   public static void addFavorite(String n) { if (!isFavorite(n)) FAV.add(n.trim().toLowerCase());  }
/* 11 */   public static void removeFavorite(String n) { FAV.remove(n.trim().toLowerCase()); }
/* 12 */   public static List<String> getFavorites() { return new ArrayList<>(FAV); } public static boolean hasFavorites() {
/* 13 */     return !FAV.isEmpty();
/*    */   }
/*    */ }


/* Location:              D:\Minecraft\Minecraft Java\jars\Disc_Jockey_Plus 测试版\2.x\2.0.1\可用文件\DJP-Fabric-2.0.1-rc-0.0.13+mc26.2.jar!\semmiedev\disc_jockey\FavoritesManager.class
 * Java compiler version: 25 (69.0)
 * JD-Core Version:       1.1.3
 */