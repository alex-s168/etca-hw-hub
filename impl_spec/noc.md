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
/// when a sequence of noc packets is sent that needs a specific order, it might be sent like this:
///   noc_packet { flow: 4, order: 4, priority: 8 }
///   noc_packet { flow: 4, order: 9, priority: 8 }
///   noc_packet { flow: 4, order: 14, priority: 8 }
///   noc_packet { flow: 4, order: 23, priority: 8 }
///   noc_packet { flow: 4, order: 33, priority: 8 }
///   noc_packet { flow: 4, order: 40, priority: 7, also_flush_order: 1 } // low priority packet (always received as last)
/// which might be reordered in any way during routing (except that the low priority packet is last)
/// and then gets sorted on receive. When the receiver gets the packet with the also_flush_order flag,
/// it can process all the received packets on that port
/// packets with different flow are independent of each other
///
/// when sending a single packet, it needs to have also_flush_order set to 1
struct noc_packet {
    target_x_bitmask: u8,
    target_y_bitmask: u8,

    /// low 8 = etca, high 8 = special
    target_port: u4,

    /// (routing priority) higher = faster
    priority: u4,

    flow: u4,
    order: u10,
    also_flush_order: u1,

    empty: u1,

    data: [u8; 16]
}

/// in data array of a noc_packet to port `8` of a memory controler
struct mem_read {
    response_x_bitmask: u8,
    response_y_bitmask: u8,
    response_port u4,
    /// gets multiplied by 4; maximum 4 (=16 bytes)
    read_len: u4,

    /// low two bits of address have to be zero
    address: u32,
    ...pading
}

/// in data array of a noc_packet to port `9` of a memory controler
struct mem_write {
    /// gets multiplied by 4; maximum 2 (=8 bytes)
    write_len: u3,
    _reserved: u5,
    /// low two bits of address have to be zero
    address: u32,
    data: [u8; 8],
    ...padding
}
```
