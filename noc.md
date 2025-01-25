16 bytes data per NoC channel

core IDs: MSB `xxxxxxxx yyyyyyyy` LSB (`x`: grid X coordinate BITMASK, `y`: grid Y coordinate BITMASK)

packets can be multicast!

port IDs are 0-15 (4-bit), where 0-7 (3-bit) are ETC.A NoC ports, and the upper 8 are special:
| port ID   | purpose                   |
| --------- | ------------------------- |
|  `0`-`7`  | classical ETC.A NoC ports |
|  `8`      | memory read request       |
|  `9`      | memory write request      |
| `10`-`13` | memory read response port |

```
struct noc_packet {
    target_x_bitmask: u8,
    target_y_bitmask: u8,

    /// low 8 = etca, high 8 = special
    target_port: u4,

    /// (routing priority) higher = faster
    priority: u4,

    data: [u8; 16]
}

/// in data array of a noc_packet to port `8` of a memory controler
struct mem_read {
    response_x_bitmask: u8,
    response_y_bitmask: u8,
    response_port u4,
    /// maximum 16 bytes (noc width)
    read_len: u4,
    address: u32,
    ...pading
}

/// in data array of a noc_packet to port `9` of a memory controler
struct mem_write {
    /// maximum 8 bytes
    write_len: u3,
    _reserved: u5,
    address: u32,
    data: [u8; 8],
    ...padding
}
```
