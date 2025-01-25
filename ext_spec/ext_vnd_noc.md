# NoC extension
(Network-on-Chip)

**Requires: Base, Multi-Core**

**Optional: Interrupts, Priviliged Mode**

**CPUID Bit: ???**

Note that this extension developed with https://github.com/ETC-A/etca-spec/pull/74 in mind.

## Overview
Allows for fast transmission of data between cores.

Most systems that implement this extension will have their cores in a grid layout,
where data "packets" go trough other cores before they reach their final destination.

## Why
This extension allows for "fast" sharing of data between different cores,
without using the slow main memory.

It also allows for fast synchronization between cores, by sending a message to a core, and waiting for the response.

This extension can also be used by microkernels to transmit interprocess messages.

## Specification
### Participants
All cores in the system are NoC participants.
NoC pariticipants do not have to be cores.

A NoC ID is identical to a core ID,
except that there may be additional NoC participants with IDs greater or equal to `CORE_NUM`.
These are called "additional NoC participants".

NoC IDs are 16 bits wide.

`NOC_EXTRA` specifies how many additional NoC participants are available.
The behaviour of additional NoC participants is not defined.

### Packets
The time it takes for a NoC packet to arrive at the destination is not defined. 

`NOC_LEN` defines the width of NoC packets in bytes.

All NoC packets sent by one core to a specific port have to arrive in the same order at the destination NoC port.

NoC packet send is allowed to block. That could happen when the NoC router on the current core does not have any capacity left,
which could be caused by the neighbor core sending lots of data trough this core.
For more details on this, see `NSND` instruction.

### Matrix
If the `CORE_WIDTH` and `CORE_HEIGHT` control registers
do not both contain the value `0`,
then the system has something similar to a grid core layout,
with core ID 0 beeing at the top left,
and the core IDs enumerated from left to right and top to bottom.
This however, does not affect the functionality,
and is only used to hint the operating system 
how it should schedule tasks that communicate via NoC.

If `CORE_WIDTH` and `CORE_HEIGHT` are not 0, `CORE_POSX` and `CORE_POSY` have to contain the position in the matrix.

### User-Mode
If the core is not in priviliged mode, direct access to the NoC ports is disabled. 
Code can hovever access the two virtual NoC ports with ID 0 and ID 1, if enabled in the `NOC_VIRT`.

`NOC_VIRT` is a 8+8 bit control register, which represents a matrix from virtual NoC ports to physical NoC ports:
|                           | p7 | p6 | p5 | p4 | p3 | p2 | p1 | p0 |
| ------------------------- | -- | -- | -- | -- | -- | -- | -- | -- |
| virtual port 0 (LS 8 bit) |    |    |    |    |    |    |    |    |
| virtual port 1 (MS 8 bit) |    |    |    |    |    |    |    |    |
(p0 is the least significant bit of each virtual port)

the following behaviours apply for each port, when a message is received from that virtual port:
- if at least one of the 8 bits is enabled:
  the message is received from that physical port
- if none of the 8 bits is enabled:
  a gp fault is triggered
- in all other cases:
  undefined behaviour

If the code (in user-mode) tries to receive a message from a non-virtual port, a gp fault is triggered.

### Recv-Ints
If the processor supports the interrupt extension, the `NOC_INTMASK` register (zero-initialized on startup) becomes available.

If the processor supports interrupts, the following interrupts are added:
| Name                     | Type                    | Cause Value | Description                               |
|:-------------------------|:------------------------|:------------|:------------------------------------------|
| NoC Receive              | Asynchronous (per core) | 6           | Occurs when NoC data is sent to this core |

```
If the core receives a NoC message:
  - Wait for the core to leave any current interrupt handlers (since this interrupt is asynchrounous)
  - If the NoC receive interrupt (6) is enabled in the `INT_MASK`:
     If the **physical** NoC port is enabled in the `NOC_INTMASK`:
       - Set `INT_DATA` to the ID of the physical NoC port
       - Trigger the interrupt
```

### Control Registers
| CRN      | Name          | Description            | priviliged-mode read | priviliged-mode write | user-mode read | user-mode write |
| -------- | ------------- | ---------------------- | -------------------- | --------------------- | -------------- | --------------- |
| `??????` | `CORE_WIDTH`  | see #Matrix            | 8-bit value          | undefined             | 8-bit value    | gp fault        |
| `??????` | `CORE_HEIGHT` | see #Matrix            | 8-bit value          | undefined             | 8-bit value    | gp fault        |
| `??????` | `CORE_POSX`   | see #Matrix            | 8-bit value          | undefined             | 8-bit value    | gp fault        |
| `??????` | `CORE_POSY`   | see #Matrix            | 8-bit value          | undefined             | 8-bit value    | gp fault        |
| `??????` | `NOC_EXTRA`   | see #Participants      | 16-bit value         | undefined             | gp fault       | gp fault        |
| `??????` | `NOC_LEN`     | NoC messsage size (3)  | 8-bit value          | undefined             | 8-bit value    | gp fault        |
| `??????` | `NOC_VIRT`    | see #User-Mode (1)     | undefined            | 16-bit value          | gp fault       | gp fault        |
| `??????` | `NOC_INTMASK` | see #Recv-Ints (1) (2) |  8-bit value         | 8-bit value           | gp fault       | gp fault        |

1) Initialized with all zeros on core startup

2) Only accessible if the interrupt extension is present!

3) This value has to be identical on all cores

### Opcode Table
#### NSND
Notes: 1, 2, 3
Arguments:
- target NoC ID: 16 bit in register
- target NoC port: 3 bit in register
- pointer to data; needs to be as long as `NOC_LEN`
sends a NoC packet of `NOC_LEN` to the target.

#### NRECV
Notes: 2, 3
Arguments
- NoC port: 3 bit in register
- pointer to data; needs to be as long as `NOC_LEN`
Waits for a NoC message to be available on the given port, 
and moves the data into the given pointer.

#### NAVL
Arguments:
- output register
Output:
- a 8 bit value, where the LSB represents port 0, and the MSB represents port 7
behaviour depends on weather or not in priviliged mode:
- if in priviliged mode:
  represents which physical ports have data
- if in user mode:
  represents which virtual ports have data

1) Triggers a GP fault if not in priviliged mode
2) Might block
3) When interrupted: reverts operation, and re-does it when interrupt is done

## Impelemtation Recommendations
For a simple NoC routing implementation, core IDs should represent a 2D location in the core matrix

## Example
```c
uint8_t NOC_LEN();
void noc_recv(uint8_t port, char * data); // NSND
uint8_t noc_available_mask(); // NAVL
// not supported in user-mode
void noc_send(uint16_t target_id, uint8_t target_port, char * data); // NRECV

// not supported in user-mode
void noc_sendn(uint16_t target_id, uint8_t target_port, char * data, size_t data_len) {
  while (data_len >= NOC_LEN()) {
    noc_send(target_id, target_port, data);
    data += NOC_LEN();
    data_len -= NOC_LEN();
  }
  if (data_len > 0) {
    char buf[NOC_LEN()];
    memcpy(buf, data, data_len);
    noc_send(target_id, target_port, buf);
  }
}

// N HAS TO BE THE SAME LENGTH AS N IN NOC_SENDN
void noc_recvn(uint8_t port, char * data, size_t n) {
  while (n > 0) {
    char buf[NOC_LEN()];
    noc_recv(port, buf);
    size_t mc = n % NOC_LEN();
    memcpy(data, buf, mc);
    data += mc;
  }
}
```
