/*
 * Minimal talloc stub for cross-compiling proot with Android NDK.
 * Replaces the real Samba talloc with a simple malloc/free wrapper.
 * Parent-child tracking is intentionally omitted — acceptable for proot
 * since cleanup happens via process exit.
 */
#ifndef TALLOC_H
#define TALLOC_H

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>

typedef void TALLOC_CTX;

/* Core allocation */
void *_talloc(const void *ctx, size_t size);
void *talloc_zero_size(const void *ctx, size_t size);
void *talloc_named_const(const void *ctx, size_t size, const char *name);
void *_talloc_zero(const void *ctx, size_t size, const char *name);
void *_talloc_memdup(const void *t, const void *p, size_t size, const char *name);

/* Deallocation */
int  talloc_free(void *ptr);
void talloc_free_children(void *ptr);

/* Strings */
char *talloc_strdup(const void *ctx, const char *str);
char *talloc_strndup(const void *ctx, const char *str, size_t n);
char *talloc_asprintf(const void *ctx, const char *fmt, ...) __attribute__((format(printf,2,3)));
char *talloc_vasprintf(const void *ctx, const char *fmt, va_list ap);
char *talloc_asprintf_append(char *s, const char *fmt, ...) __attribute__((format(printf,2,3)));
char *talloc_asprintf_append_buffer(char *s, const char *fmt, ...) __attribute__((format(printf,2,3)));

/* Misc */
void       *talloc_steal(const void *new_ctx, const void *ptr);
void       *talloc_move(const void *new_ctx, void **ptr);
int         talloc_increase_ref_count(const void *ptr);
const char *talloc_get_name(const void *ptr);
void        talloc_set_name_const(const void *ptr, const char *name);
void       *talloc_new(const void *ctx);
void       *talloc_init(const char *name);
void       *talloc_pool(const void *ctx, size_t size);
void       *talloc_parent(const void *ptr);
size_t      talloc_total_size(const void *ptr);
size_t      talloc_total_blocks(const void *ptr);
void        talloc_report_full(const void *ptr, FILE *f);
void        talloc_report(const void *ptr, FILE *f);
void        talloc_enable_leak_report(void);
void        talloc_enable_leak_report_full(void);
bool        talloc_is_parent(const void *ctx, const void *ptr);
void       *_talloc_realloc(const void *ctx, void *ptr, size_t size, const char *name);
void       *_talloc_steal_loc(const void *new_ctx, const void *ptr, const char *location);
int         talloc_reference_count(const void *ptr);
int         talloc_unlink(const void *ctx, void *ptr);

/* Macros */
#define talloc(ctx, type)           ((type *)_talloc(ctx, sizeof(type)))
#define talloc_zero(ctx, type)      ((type *)talloc_zero_size(ctx, sizeof(type)))
#define talloc_array(ctx, type, n)  ((type *)_talloc(ctx, sizeof(type) * (n)))
#define talloc_zero_array(ctx, type, n) ((type *)talloc_zero_size(ctx, sizeof(type) * (n)))
#define talloc_size(ctx, size)      _talloc(ctx, size)
#define talloc_ptrtype(ctx, ptr)    ((__typeof__(ptr))_talloc(ctx, sizeof(*(ptr))))
#define talloc_new(ctx)             _talloc(ctx, 1)
#define talloc_memdup(ctx, p, size) _talloc_memdup(ctx, p, size, __location__)
#define talloc_realloc(ctx, p, type, n) \
    ((type *)_talloc_realloc(ctx, p, sizeof(type) * (n), __location__))
#define talloc_realloc_size(ctx, p, size) \
    _talloc_realloc(ctx, p, size, __location__)

#define __location__ __FILE__ ":" __STRINGIFY__(__LINE__)
#define __STRINGIFY__(x) __TOSTRING__(x)
#define __TOSTRING__(x) #x

#define TALLOC_MAX_DEPTH 10000

#endif /* TALLOC_H */
