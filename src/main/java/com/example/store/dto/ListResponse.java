package com.example.store.dto;

public sealed interface ListResponse<T> permits PageResponse, CursorPageResponse {}
