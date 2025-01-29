# Asynchrounous Memory Access Extension

## Why?
Implementations that have core-local memory tend to have high-delay, high-bandwidth memory,
which makes traditional memcpy routines slow, because the store instruction (probably) waits for the memory to be written.

This extensions makes the above scenario way faster and also allows the core to copy data in the background while the program is running.

This extension can only be used in privilige mode.

If the addresses are not word-aligned, or the amount of bytes is not divisible by 2,
and the system does not support the unaligned memory feature, undefined behaviour.

Note that this extension is not a proper substitute for DMA controlers for multiple reasons,
but that is not what this extension was designed for.

## Added Instructions 
- ASYNC_COPY <destaddr>, <srcaddr>
  The amount of bytes to copy is stored in `r3`.
  If this is called and `ASYNC_COPY_DONE` is not 1, undefined behaviour.

## Added Control Registers
- `ASYNC_COPY_DONE` when a copy is in process, this is zero, otherwise, this is one

## Example
```c
void ASYNC_COPY(void* destaddr, void const* srcaddr, size_t amount);

// ONLY CALL THIS IN PRIVLIGED MODE
// NOTE THAT THIS DOES NOT HANDLE ALIGNMENT
void memcpy(void* dest, void const* src, size_t num) {
  if (has_ext(ASYNC_MEM)) {
    while (!getcr(ASYNC_COPY_DONE)) ;
    ASYNC_COPY(dest, src, num);
    while (!getcr(ASYNC_COPY_DONE)) ;
    return;
  }
  for (size_t i = 0; i < num; i += 2) {
    *((uint16_t*)(dest+i)) = *((uint16_t*)(src+i));
  }
}
```
