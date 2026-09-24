 package semmiedev.disc_jockey;
 
 import java.util.ArrayList;
 import java.util.List;
 
 public class FavoritesManager {
   private static final List<String> FAV = new ArrayList<>();
   
   public static boolean isFavorite(String n) { return FAV.contains(n.trim().toLowerCase()); }
   public static void addFavorite(String n) { if (!isFavorite(n)) FAV.add(n.trim().toLowerCase());  }
   public static void removeFavorite(String n) { FAV.remove(n.trim().toLowerCase()); }
   public static List<String> getFavorites() { return new ArrayList<>(FAV); } public static boolean hasFavorites() {
     return !FAV.isEmpty();
   }
 }


