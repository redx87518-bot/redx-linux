/*
 * Minimal talloc stub implementation.
 * Uses standard malloc/free. No parent-child tracking.
 */
#include "talloc.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

void *_talloc(const void *ctx, size_t size) {
    return malloc(size);
}

void *talloc_zero_size(const void *ctx, size_t size) {
    return calloc(1, size);
}

void *talloc_named_const(const void *ctx, size_t size, const char *name) {
    return malloc(size);
}

void *_talloc_zero(const void *ctx, size_t size, const char *name) {
    return calloc(1, size);
}

void *_talloc_memdup(const void *t, const void *p, size_t size, const char *name) {
    void *r = malloc(size);
    if (r && p) memcpy(r, p, size);
    return r;
}

int talloc_free(void *ptr) {
    if (ptr) free(ptr);
    return 0;
}

void talloc_free_children(void *ptr) {
    /* no-op: no child tracking */
}

char *talloc_strdup(const void *ctx, const char *str) {
    if (!str) return NULL;
    return strdup(str);
}

char *talloc_strndup(const void *ctx, const char *str, size_t n) {
    if (!str) return NULL;
    size_t len = strnlen(str, n);
    char *s = malloc(len + 1);
    if (s) {
        memcpy(s, str, len);
        s[len] = '\0';
    }
    return s;
}

char *talloc_asprintf(const void *ctx, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char *s = talloc_vasprintf(ctx, fmt, ap);
    va_end(ap);
    return s;
}

char *talloc_vasprintf(const void *ctx, const char *fmt, va_list ap) {
    va_list ap2;
    va_copy(ap2, ap);
    int len = vsnprintf(NULL, 0, fmt, ap2);
    va_end(ap2);
    if (len < 0) return NULL;
    char *s = malloc(len + 1);
    if (s) vsnprintf(s, len + 1, fmt, ap);
    return s;
}

char *talloc_asprintf_append(char *s, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int slen = s ? (int)strlen(s) : 0;
    int alen = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    char *r = realloc(s, slen + alen + 1);
    if (r) {
        va_start(ap, fmt);
        vsnprintf(r + slen, alen + 1, fmt, ap);
        va_end(ap);
    }
    return r;
}

char *talloc_asprintf_append_buffer(char *s, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char *r = talloc_asprintf_append(s, fmt, ap);
    va_end(ap);
    return r;
}

void *talloc_steal(const void *new_ctx, const void *ptr) {
    return (void *)ptr;
}

void *talloc_move(const void *new_ctx, void **ptr) {
    void *p = *ptr;
    *ptr = NULL;
    return p;
}

int talloc_increase_ref_count(const void *ptr) {
    return 0;
}

const char *talloc_get_name(const void *ptr) {
    return "unknown";
}

void talloc_set_name_const(const void *ptr, const char *name) {
    /* no-op */
}

void *talloc_pool(const void *ctx, size_t size) {
    return malloc(size);
}

void *talloc_parent(const void *ptr) {
    return NULL;
}

size_t talloc_total_size(const void *ptr) {
    return 0;
}

size_t talloc_total_blocks(const void *ptr) {
    return 0;
}

void talloc_report_full(const void *ptr, FILE *f) {
    /* no-op */
}

void talloc_report(const void *ptr, FILE *f) {
    /* no-op */
}

void talloc_enable_leak_report(void) {}
void talloc_enable_leak_report_full(void) {}

bool talloc_is_parent(const void *ctx, const void *ptr) {
    return false;
}

void *_talloc_realloc(const void *ctx, void *ptr, size_t size, const char *name) {
    return realloc(ptr, size);
}

void *_talloc_steal_loc(const void *new_ctx, const void *ptr, const char *location) {
    return (void *)ptr;
}

int talloc_reference_count(const void *ptr) {
    return 1;
}

int talloc_unlink(const void *ctx, void *ptr) {
    return 0;
}
