package com.stockkeeperplus.client;

/**
 * Implemented by a Stock Keeper screen that can draw something over Create's item grid
 * (the Factory Gauges tab). While it does, Create must not treat the grid as hovered.
 */
public interface ItemGridCover {
	boolean stockkeeperplus$coversItemGrid();
}
