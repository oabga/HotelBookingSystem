package com.hotelbooking.catalog.service;

public class CatalogNotFoundException extends RuntimeException {
    public CatalogNotFoundException() {
        super("Resource not found");
    }
}
