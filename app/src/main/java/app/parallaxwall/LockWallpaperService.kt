package app.parallaxwall

/**
 * 鎖定畫面專用的桌布項目。內容與引擎完全共用 [ParallaxWallpaperService]，
 * 只是讓系統把它當成另一個桌布，才能和主畫面分別套用、各顯示不同素材。
 */
class LockWallpaperService : ParallaxWallpaperService() {
    override val slot: String get() = Store.LOCK
}
