# Asynchronous NoC extension
**W I P**

Depends on: NoC

## Why?
This extension allows programs to receive NoC data whilst doing something else,
making NoC way more useful.

This can only be used in **priviliged mode**, for simplicity reasons.

Implementing this extension **without** having core-local memory is __stupid__

There are `NUM_NOC_ASYNC` async NoC controllers, which is at least one.

You don't need to NFLSH after async noc send

If there are multiple async NoC controllers, this can also be used to multi-cast packets.

## Instructions
### Async NoC configure
Arguments:
- Async NoC controller ID
- should send or should receive?
- pointer to buffer of size `NOC_LEN` * mult
- mult: how many NoC packets to receive/send before this is finished.
- which NoC port to send to / receive from
- NoC ID of the target (only if send)

### Async NoC done?

TODO: memory map async NoC controlers instead

## Example
```c
void noc_recv(char * data, uint8_t port); // NRCV
uint8_t noc_available_mask(); // NAVL
void noc_send(uint16_t target_id, uint8_t target_port, char * data); // NSND
void noc_flush(uint16_t target_id, uint8_t target_port); // NFLSH
void noc_async_cfg(uint8_t ctl, bool send, char* buf, size_t mult, uint8_t nocport, uint16_t target);
bool noc_async_done(uint8_t port);

// not optimal at all!
uint8_t noc_sendn(uint16_t target_id, uint8_t target_port, char * data, size_t data_len) {
  if (data_len % getcr(NOC_LEN) != 0) {
    data_len /= getcr(NOC_LEN);
    data_len ++;
    data_len *= getcr(NOC_LEN);
  }

  // should also consider using other ports
  if (has_ext(ASYNC_NOC) && noc_async_done(0)) {
    noc_async_cfg(0, true, data, data_len / getcr(NOC_LEN), target_port, target_id);
    return 0;
  }
  else {
    while (data_len) {
      noc_send(target_id, target_port, data);
      data += NOC_LEN();
      data_len -= getcr(NOC_LEN);
    }
    noc_flush(target_id, target_port);
    return UINT8_MAX;
  }
}

void example() {
  char buf[] = "A lot of dataaaaa";
  uint8_t ctl = noc_sendn(123, 1, buf, sizeof(buf));
  if (ctl != UINT8_MAX) {
    while (!noc_async_done(ctl)) ;
  }
}
```
