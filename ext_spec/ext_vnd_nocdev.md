# NoC Extra-Devices Extension
Depends on NoC extension.

This extension adds no processor or core features. It only specifies the behaviour of additional NoC participants.

This extension only defines the behaviour of NoC port `0` on all additional NoC participants.

All messages sent **to** an additional NoC participant have to look like this:
```
struct {
  u8 function;
  u8 payload_size;
  u8[payload_size] payload;
}
```
The message (NOT the payload) gets padded to a multiple of the NoC packet size.

Response from the devices can be in any format and depend on the function.

**All** additional NoC participants have to support function `0x00`

## Function `0x00`: query device information
input (payload):
```
struct {
  u16 response_noc;
  u4 response_port;
  u4 description_target;
}
```
sends varialbe length data to `response_noc:response_port` afterwards:
```
struct {
  u8 data_len;
  u8[data_len] data;
}
```

The following `description_target`s are available:
| Target | Description | Response data format                |
| ------ | ----------- | ----------------------------------- |
| `0x00` | device ID   | `{ u16 vendor_id; u16 device_id; }` |
Using other `description_target`s is device dependent.

## Device ID Registry
| Vendor        | Device ID | Description                           |
| ------------- | --------- | ------------------------------------- |
| 0x0000 = None | 0x0000    | processor temperature sensor (#ctemp) |

### ctemp
Temperature sensor for all cores and the processor itself.

#### Function `0x01`: get temperature
input:
```
struct {
  u16 response_noc;
  u4 response_port;

  enum : u4 {
    MODE_CPU = 0,
    MODE_CORE = 1,
  } target;
  u16 subtarget;
}
```

sends this to `response_noc:response_port` afterwards:
```
struct {
  u16 max_temp;
  i16 temp;

  u16 power_watt;
  u16 power_watt_max;
}
```
If `power_watt_max` is `0`, it means that the sensor cannot get the wattage usage for that target.
If `max_tmp` is `0`, it means that the sensor cannot get the temperature for that target.
`temp` is the actual temperature multiplied by `100`.

If the target is `MODE_CORE`, then `subtarget` is the NoC- / Core- ID of the target.

If the target is `MODE_CPU`:
| `subtarget` | Description       |
| ----------- | ----------------- |
| `0x0000`    | Processor Die     |
| `0x0001`    | Processor Package |

## Example
list all available NoC devices (priviliged mode)
```c
void noc_recv(char * data, uint8_t port); // NRCV
uint8_t noc_available_mask(); // NAVL
void noc_send(uint16_t target_id, uint8_t target_port, char * data); // NSND
void noc_flush(uint16_t target_id, uint8_t target_port); // NFLSH

void noc_sendn(uint16_t target_id, uint8_t target_port, char * data, size_t data_len) {
  while (data_len >= getcr(NOC_LEN)) {
    noc_send(target_id, target_port, data);
    data += NOC_LEN();
    data_len -= getcr(NOC_LEN);
  }
  if (data_len > 0) {
    char buf[getcr(NOC_LEN)];
    memcpy(buf, data, data_len);
    noc_send(target_id, target_port, buf);
  }
  noc_flush(target_id, target_port);
}

void nocdev_send(uint16_t target_noc, uint8_t function, uint8_t payload_len, char * payload) {
  char data[2 + payload_len];
  data[0] = function;
  data[1] = payload_len;
  memcpy(data + 2, payload, payload_len);
  noc_sendn(target_noc, 0, data, 2 + payload_len);
}

// N HAS TO BE THE SAME LENGTH AS N IN NOC_SENDN
void noc_recvn(uint8_t port, char * data, size_t n) {
  while (n > 0) {
    char buf[getcr(NOC_LEN)];
    noc_recv(buf, port);
    size_t mc = n % getcr(NOC_LEN);
    memcpy(data, buf, mc);
    data += mc;
  }
}

// receive variable length data where u8 prefix says length
void noc_recv_valen256(uint8_t port, char * payload, uint8_t * len_out) {
  char buf[getcr(NOC_LEN)];
  noc_recv(buf, port);
  *len_out = buf[0];

  unsigned step0 = buf[0];
  if (step0 > (getcr(NOC_LEN) - 1)) {
    step0 = getcr(NOC_LEN) - 1;
  }
  memcpy(payload, buf + 1, step0);
  noc_recvn(port, payload + step0, buf[0] - step0);
}

typedef struct {
  uint16_t response_noc;
  unsigned response_port : 4;
  unsigned description_target : 4;
} __attribute__ ((packed)) nocdev_querydescr;

typedef struct {
  uint16_t vendor;
  uint16_t device;
} __attribute__ ((packed)) nocdev_id;

nocdev_id nocdev_query(uint16_t id) {
  if (has_ext(int)) {
    // disable ints for port 0
    setcr(NOC_INTMASK, getcr(NOC_INTMASK) & 0b11111110);
  }

  nocdev_querydescr req;
  req.response_noc = getcr(CORE_ID);
  req.response_port = 0;
  req.description_target = 0x00;
  nocdev_send(id, 0x00, sizeof(req), &req);

  nocdev_id resp;
  uint8_t len_out;
  noc_recv_valen256(0, &resp, &len_out);
  (void) len_out;
  return resp;
}

...
if (hasext(nocdev)) {
  // iterate trough all extra devices
  for (uint16_t noc = getcr(CORE_NUM); noc < getcr(CORE_NUM) + getcr(NOC_EXTRA); noc ++) {
    nocdev_id id = nocdev_query(noc);
    printf("found %u:%u at %u\n", id.vendor, id.device, noc);
  }
}
```
