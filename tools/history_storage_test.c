/* Host fixture compiles the actual production functions extracted by the runner. */
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#define __packed __attribute__((packed))
#include "history_codec.h"
#include "history_storage_types.inc"
#define BUILD_ASSERT(c) typedef char assert_##__LINE__[(c)?1:-1]
#define K_FOREVER 0
#define K_NO_WAIT 0
#define K_SECONDS(n) (n)
#define K_MSEC(n) (n)
#define printk(...) ((void)0)
struct k_work { int unused; };
struct k_work_delayable { int unused; };
struct k_mutex { int unused; };
#define ARG_UNUSED(x) ((void)(x))
static uint8_t flash_bytes[W25Q64_STORAGE_SIZE];
static uint16_t next_sector, oldest_sector, next_record_in_sector, history_interval=60;
static struct data_record ram_buffer[RAM_BUFFER_SIZE];
static uint8_t ram_buffer_count, history_tx_buf[60], history_wire_size;
static bool time_synced = true, sector_switched_flag, position_dirty;
static bool data_clear_in_progress, history_transfer_active;
static history_retention_t saved_metadata;
static int nvs_failures, program_fault, erase_count;
static unsigned failures;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n",__LINE__,#c); failures++; } } while (0)
static struct k_mutex *w25q64_get_mutex(void) { return NULL; }
static void k_mutex_lock(struct k_mutex *p,int t) { (void)p;(void)t; }
static void k_mutex_unlock(struct k_mutex *p) { (void)p; }
static void k_work_schedule(struct k_work_delayable *p,int t) { (void)p;(void)t; }
static bool ble_ota_in_progress(void) { return false; }
static bool w25q64_is_ready(void) { return true; }
static void w25q64_wakeup(void) {}
static void w25q64_sleep(void) {}
static void led_start_data_blink(void) {}
static int w25q64_read(uint32_t a,uint8_t *p,size_t n) {
    if(a<W25Q64_STORAGE_BASE || a+n>W25Q64_STORAGE_BASE+sizeof(flash_bytes)) return -EINVAL;
    memcpy(p,flash_bytes+a-W25Q64_STORAGE_BASE,n); return 0;
}
static int w25q64_page_program(uint32_t a,const uint8_t *p,size_t n) {
    if(a<W25Q64_STORAGE_BASE || a+n>W25Q64_STORAGE_BASE+sizeof(flash_bytes) || a%256+n>256) return -EINVAL;
    size_t written=n;
    bool fail=(program_fault==1 && n==12) || (program_fault==2 && n==1);
    if(fail && program_fault==1) written=5; /* torn payload */
    for(size_t i=0;i<written;i++) flash_bytes[a-W25Q64_STORAGE_BASE+i] &= p[i];
    if(fail) { program_fault=0; return -EIO; }
    return 0;
}
static int w25q64_sector_erase(uint32_t a) {
    if(a<W25Q64_STORAGE_BASE || a+4096>W25Q64_STORAGE_BASE+sizeof(flash_bytes) || a%4096) return -EINVAL;
    CHECK(saved_metadata.magic==HISTORY_RETENTION_MAGIC || saved_metadata.limit==0);
    memset(flash_bytes+a-W25Q64_STORAGE_BASE,255,4096); erase_count++; return 0;
}
static int nvs_save_history_retention(const history_retention_t *p) {
    if(nvs_failures) { nvs_failures--; return -EIO; }
    saved_metadata=*p; return 0;
}
static void nvs_save_storage_position(const storage_position_t *p) { (void)p; }
#include "history_storage_under_test.inc"
static void sys_put_le16(uint16_t x,uint8_t *p) { p[0]=x;p[1]=x>>8; }
static uint32_t sys_get_le32(const uint8_t *p) { return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24); }
#include "history_wire_under_test.inc"
static void reset(void) {
    memset(flash_bytes,255,sizeof(flash_bytes)); memset(&retention,0,sizeof(retention));
    memset(&saved_metadata,0,sizeof(saved_metadata)); next_sector=oldest_sector=next_record_in_sector=0;
    ram_buffer_count=0; retention_busy=false; retention_phase=0; nvs_failures=program_fault=erase_count=0;
}
static struct data_record row(unsigned i,bool voltage) {
    struct data_record r={1700000000U+i*60U,2000,5000,100000};
    if(voltage) r.pressure_pa=history_pack_pressure(100000,2900);
    return r;
}
static void seed(unsigned n) {
    reset();
    for(unsigned i=0;i<n;i++) { struct data_record r=row(i,false); memcpy(flash_bytes+storage_record_address(i)-W25Q64_STORAGE_BASE,&r,12); }
    next_sector=n/W25Q64_RECORDS_PER_SECTOR; next_record_in_sector=n%W25Q64_RECORDS_PER_SECTOR;
}
static void run_gc(void) {
    unsigned steps=0;
    while(retention_busy && steps++<1000) retention_work_handler(NULL);
    CHECK(!retention_busy);
}
static void verify_window(unsigned first,unsigned end) {
    CHECK(retention.count==end-first);
    CHECK((unsigned)retention.first_sector*W25Q64_RECORDS_PER_SECTOR+retention.first_record==first%HISTORY_SLOT_COUNT);
    for(unsigned i=first;i<end;i++) {
        struct data_record r; CHECK(w25q64_read(storage_record_address(i%HISTORY_SLOT_COUNT),(uint8_t*)&r,12)==0);
        CHECK(storage_valid_record(&r)); CHECK(r.timestamp==1700000000U+i*60U);
        uint32_t pa;uint16_t mv; CHECK(history_decode_pressure(r.pressure_pa,&pa,&mv));
        CHECK(pa==100000); CHECK(mv==(i<33000?65535:2900));
    }
}
static void codecs(void) {
    uint32_t pa;uint16_t mv;
    CHECK(history_decode_pressure(100000,&pa,&mv) && pa==100000 && mv==65535);
    CHECK(history_decode_pressure(history_pack_pressure(200000,3000),&pa,&mv) && pa==200000 && mv==3000);
    CHECK(history_decode_pressure(history_pack_pressure(10000,65535),&pa,&mv) && mv==65535);
    CHECK(!history_decode_pressure(history_pack_pressure(9999,3000),&pa,&mv));
    CHECK(!history_decode_pressure(history_pack_pressure(100000,3000)|0x40000000U,&pa,&mv));
    uint8_t bytes[12];memset(bytes,255,12);CHECK(history_bytes_erased(bytes,12));bytes[11]=0xfe;
    CHECK(!history_bytes_erased(bytes,12));
    reset();struct data_record r=row(0,false);r.timestamp=0x123400ffU;
    memcpy(flash_bytes,&r,12); storage_scan_flash_for_write_head(0);
    CHECK(next_record_in_sector==1); /* low timestamp byte FF is occupied */
}
static void retention_days(void) {
    reset();history_interval=60;CHECK(history_retention_days()==45);
    retention.limit=3000;CHECK(history_retention_days()==2);
    history_interval=300;CHECK(history_retention_days()==10);
    history_interval=3600;CHECK(history_retention_days()==125);
    history_interval=60;reset();
}
static void wires(void) {
    uint8_t bytes[60],len;
    for(unsigned format=12;format<=14;format+=2) {
        history_wire_size=format;len=0;memset(bytes,0,60);
        for(unsigned i=0;i<4;i++) {
            struct data_record r=row(i,true);uint32_t pa;uint16_t mv;
            CHECK(history_decode_pressure(r.pressure_pa,&pa,&mv));
            len=append_actual_wire(r,pa,mv,bytes,len);
            CHECK(sys_get_le32(bytes+i*format+8)==100000);
            if(format==14) CHECK(bytes[i*format+12]==(2900&255) && bytes[i*format+13]==2900/256);
        }
        CHECK(len==4*format);CHECK(actual_last_timestamp(bytes,len)==1700000000U+3U*60U);
    }
    struct data_record old=row(0,false);uint32_t pa;uint16_t mv;
    CHECK(history_decode_pressure(old.pressure_pa,&pa,&mv));history_wire_size=14;
    CHECK(append_actual_wire(old,pa,mv,bytes,0)==14);CHECK(bytes[12]==255 && bytes[13]==255);
}
static void pruning_and_retention(void) {
    seed(33000);nvs_failures=1;CHECK(retention_request(3000)==0);
    while(retention_phase!=2) retention_work_handler(NULL);
    CHECK(erase_count==0); /* failed metadata commit cannot erase */
    run_gc();verify_window(30000,33000);
    for(unsigned i=0;i<30000;i++) {
        uint8_t *p=flash_bytes+storage_record_address(i)-W25Q64_STORAGE_BASE;
        if(i/336<30000/336) CHECK(history_bytes_erased(p,12)); else { unsigned j;for(j=0;j<12;j++) CHECK(p[j]==0); }
    }
    for(unsigned i=33000;i<33700;i++) {
        ram_buffer[0]=row(i,true);ram_buffer_count=1;storage_write_batch();CHECK(ram_buffer_count==0);run_gc();
    }
    verify_window(30700,33700);
    retention=saved_metadata;next_sector=retention.head_sector;next_record_in_sector=retention.head_record;oldest_sector=retention.first_sector;
    storage_scan_flash_for_write_head(oldest_sector);retention_scan_begin();int r;do{r=retention_scan_step();}while(r==1);CHECK(r==0);
    verify_window(30700,33700); /* old checkpoint + actual flash recovers exact window */
    for(unsigned i=33700;i<67200;i++) {
        ram_buffer[0]=row(i,true);ram_buffer_count=1;storage_write_batch();CHECK(ram_buffer_count==0);run_gc();
    }
    verify_window(64200,67200); /* cross the physical ring boundary with retention enabled */
}
static void interrupted_reclamation(void) {
    seed(33000);CHECK(retention_request(3000)==0);
    unsigned steps=0;while(erase_count<3 && steps++<1000) retention_work_handler(NULL);
    CHECK(saved_metadata.magic==HISTORY_RETENTION_MAGIC);verify_window(30000,33000);
    retention=saved_metadata;next_sector=retention.head_sector;next_record_in_sector=retention.head_record;oldest_sector=retention.first_sector;
    storage_scan_flash_for_write_head(oldest_sector);retention_scan_begin();int r;do{r=retention_scan_step();}while(r==1);CHECK(r==0);
    retention_busy=true;retention_phase=2;run_gc();verify_window(30000,33000);
}
static void lost_ack_batch_at_sector_boundary(void) {
    seed(33000);CHECK(retention_request(3000)==0);run_gc();
    for(unsigned i=33000;i<33264;i++) {
        ram_buffer[0]=row(i,true);ram_buffer_count=1;storage_write_batch();run_gc();
    }
    ram_buffer[0]=row(33264,true);ram_buffer[1]=row(33265,true);ram_buffer_count=2;
    program_fault=2;storage_write_batch();CHECK(ram_buffer_count==2);
    storage_write_batch();CHECK(retention_busy);CHECK(ram_buffer_count==1);
    run_gc();storage_write_batch();CHECK(ram_buffer_count==0);run_gc();
    verify_window(30266,33266);
}
static void failed_programs(void) {
    seed(1);ram_buffer[0]=row(1,true);ram_buffer_count=1;program_fault=1;storage_write_batch();
    CHECK(ram_buffer_count==1);CHECK(next_record_in_sector==1);
    storage_write_batch();CHECK(ram_buffer_count==0);CHECK(next_record_in_sector==3);
    struct data_record r;w25q64_read(storage_record_address(1),(uint8_t*)&r,12);CHECK(!storage_valid_record(&r));
    w25q64_read(storage_record_address(2),(uint8_t*)&r,12);CHECK(storage_valid_record(&r));
    uint32_t erased_timestamp=0xffffffffU;memcpy(flash_bytes+storage_record_address(1)-W25Q64_STORAGE_BASE,&erased_timestamp,4);
    uint16_t sector,index;
    CHECK(linear_find_first_after_timestamp(1700000000U,&sector,&index));CHECK(sector==0 && index==2);
    /* A nonempty torn slot whose timestamp remains FF must not hide later committed rows. */
    seed(1);ram_buffer[0]=row(1,true);ram_buffer_count=1;program_fault=2;storage_write_batch();
    CHECK(ram_buffer_count==1);storage_write_batch();CHECK(ram_buffer_count==0);CHECK(next_record_in_sector==2);
    /* Lost commit acknowledgment must not duplicate the row into a second slot. */
}
static void stale_sector_head_recovery(void) {
    /* Reproducible stale-checkpoint scenarios; not asserted incident NVS state. */
    const unsigned first = 99U * W25Q64_RECORDS_PER_SECTOR;
    for(unsigned scenario=0;scenario<2;scenario++) {
        const unsigned count=scenario ? 28U : 14U;
        const unsigned checkpoint=scenario ? 14U : 0U;
        seed(first+count);
        uint8_t preserved[28U*12U], occupied;
        for(unsigned i=0;i<count;i++)
            memcpy(preserved+i*12,flash_bytes+storage_record_address(first+i)-W25Q64_STORAGE_BASE,12);
        next_sector=99;next_record_in_sector=checkpoint;oldest_sector=0;
        /* Old init's head-byte-is-FF gate fails, so recovery scan is reached. */
        CHECK(w25q64_read(storage_record_address(first+checkpoint),&occupied,1)==0 && occupied!=0xff);
#ifdef HAVE_OLD_SCAN_CONTROL
        old_storage_scan_flash_for_write_head(oldest_sector);
        CHECK(next_sector==99 && next_record_in_sector==0);
        printf("Old baseline actual scan: stale99:%u + %u occupied rows ->99:0\n",checkpoint,count);
#endif
        next_sector=99;next_record_in_sector=checkpoint;oldest_sector=0;
        storage_scan_flash_for_write_head(oldest_sector);
        CHECK(next_sector==99 && next_record_in_sector==count);
        CHECK(erase_count==0);
        ram_buffer[0]=row(first+count,true);ram_buffer_count=1;
        storage_write_batch();
        CHECK(ram_buffer_count==0 && next_sector==99 && next_record_in_sector==count+1U);
        CHECK(erase_count==0);
        for(unsigned i=0;i<count;i++)
            CHECK(memcmp(preserved+i*12,flash_bytes+storage_record_address(first+i)-W25Q64_STORAGE_BASE,12)==0);
        struct data_record appended;
        CHECK(w25q64_read(storage_record_address(first+count),(uint8_t*)&appended,12)==0);
        CHECK(storage_valid_record(&appended) && appended.timestamp==row(first+count,true).timestamp);
        printf("Current actual scan + append: recovered99:%u, preserved all%u, zero erases\n",count,count);
    }
}

int main(void) {
    stale_sector_head_recovery();codecs();retention_days();wires();pruning_and_retention();interrupted_reclamation();failed_programs();lost_ack_batch_at_sector_boundary();
    printf("Production history C fixture: %u failures\n",failures);return failures?1:0;
}
