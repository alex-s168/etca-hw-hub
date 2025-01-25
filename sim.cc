#include "verilated.h"
#include "obj_dir/Vetca_core.h"

#include <unistd.h>
#include <vector>
#include <cstdint>
#include <fstream>

static std::vector<uint8_t> readfile(const char * file)
{
    auto st = std::ifstream(file, std::ios::binary);
    st.seekg(0, std::ios::end );
    std::streampos fsize = st.tellg();
    st.seekg(0);

    auto vec = std::vector<uint8_t>();
    vec.resize(fsize);
    st.read(reinterpret_cast<char*>(vec.data()), fsize);
    st.close();

    return vec;
}

void core_step(Vetca_core* core)
{
    core->clock = 1;
    core->eval();
    core->clock = 0;
    core->eval();
}

uint32_t core_read(Vetca_core* core, uint32_t addr)
{
    do {
        core->io_ram_have_req = 1;
        core->io_ram_req_addr = addr;
        core->io_ram_req_iswr = 0;
        core->io_ram_req_wrd = 0;
        core_step(core);
    }
    while (!(volatile bool)core->io_ram_finished);
    uint32_t data = core->io_ram_data;
    core->io_ram_have_req = 0;
    core->io_ram_req_addr = 0;
    core->io_ram_req_iswr = 0;
    core->io_ram_req_wrd = 0;
    core_step(core);
    return data;
}

void core_write(Vetca_core* core, uint32_t addr, uint32_t value)
{
    do {
        core->io_ram_have_req = 1;
        core->io_ram_req_addr = addr;
        core->io_ram_req_iswr = 1;
        core->io_ram_req_wrd = value;
        core_step(core);
    }
    while (!(volatile bool)core->io_ram_finished);
    core->io_ram_have_req = 0;
    core->io_ram_req_addr = 0;
    core->io_ram_req_iswr = 0;
    core->io_ram_req_wrd = 0;
    core_step(core);
}

void core_writen_pad4(Vetca_core* core, uint32_t begin, uint8_t const* data, size_t len)
{
    size_t rem = len;
    for (size_t i = 0; i < len; i += 4) {
        char buf[4] = {0};

        size_t num = rem;
        if (num > 4) num = 4;
        memcpy(buf, data + i, num);

        printf("[%zu/%zu] copying memory\r", i, len);
        fflush(stdout);
        core_write(core, begin + i, *(uint32_t*)buf);
        rem -= num;
    }
    printf("[%zu/%zu] copying memory\n", len, len);
}

void core_dump(Vetca_core* core)
{
    printf("FF=%i%i%i%i\t", (core->io_core_flags>>3)&1, (core->io_core_flags>>1)&1, (core->io_core_flags>>2)&1, (core->io_core_flags>>0)&1);
    printf("PC=%x\t", core->io_core_pc);
    printf("R0=%x\t", core->io_core_regs_0);
    printf("R1=%x\t", core->io_core_regs_1);
    printf("R2=%x\t", core->io_core_regs_2);
    printf("R3=%x\t", core->io_core_regs_3);
    printf("R4=%x\t", core->io_core_regs_4);
    printf("R5=%x\t", core->io_core_regs_5);
    printf("R6=%x\t", core->io_core_regs_6);
    printf("R7=%x\t", core->io_core_regs_7);
}

int main(int argc, char ** argv)
{
    if (argc != 2) return 1;
    std::vector<uint8_t> exec = readfile(argv[1]);

    Verilated::debug(0);
    Verilated::randReset(2);
    Verilated::traceEverOn(false);

    Vetca_core* core = new Vetca_core;
    core->clock = 0;
    core->reset = 0;
    core->io_ram_have_req = 0;
    core->io_ram_req_addr = 0;
    core->io_ram_req_iswr = 0;
    core->io_ram_req_wrd = 0;
    core->io_reset = 1;

    // hw init (needed for verilator or sth)
    core_step(core);

    core_writen_pad4(core, 0x8000, exec.data(), exec.size());

    core->io_reset = 0;

    core_step(core); // core init

    while (true) {
        core_dump(core);
        printf("\n");
        fflush(stdout);
        core_step(core);
        usleep(200/*ms*/*1000);
    }

    printf("\n");

    core->final();
}
