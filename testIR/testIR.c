#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <modbus/modbus.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <serial_port>\nExample: %s /dev/ttyS1\n", argv[0], argv[0]);
        return 1;
    }

    const char *port = argv[1];
    const int target_id = 40;

    // Initialize Modbus RTU: 9600 baud, No parity, 8 data bits, 1 stop bit
    modbus_t *ctx = modbus_new_rtu(port, 9600, 'N', 8, 1);
    if (!ctx) {
        fprintf(stderr, "Unable to create libmodbus context\n");
        return 1;
    }

    // Enable debug to see the raw hex frames (06 00 0C F0 F0 ...)
    modbus_set_debug(ctx, 1);

    // Set response timeout (500ms)
    modbus_set_response_timeout(ctx, 0, 500000);

    if (modbus_connect(ctx) == -1) {
        fprintf(stderr, "Connection failed: %s\n", modbus_strerror(errno));
        modbus_free(ctx);
        return 1;
    }

    printf("\n--- Targeting Modbus ID: %d ---\n", target_id);
    modbus_set_slave(ctx, target_id);

    // 1. Send Write Command: Write 0xF0F0 to Register Address 12 (0x000C)
    // This generates the frame: 28 06 00 0C F0 F0 + CRC (where 28 hex = 40 dec)
    if (modbus_write_register(ctx, 0x000C, 0xF0F0) == -1) {
        fprintf(stderr, "Initial Write failed: %s\n", modbus_strerror(errno));
    } else {
        printf("Successfully wrote 0xF0F0 to register 0x000C\n");
    }

    // 2. Polling Loop: Check Reg 4 until > 0 or 30 seconds pass
    printf("Starting 30-second poll for Register 4...\n");
    time_t start_poll = time(NULL);
    int success = 0;

    while (difftime(time(NULL), start_poll) < 30) {
        uint16_t regs[4] = {0};
        
        // Read 4 registers starting at address 1 (Register 2, 3, 4, 5)
        int rc = modbus_read_registers(ctx, 1, 4, regs);

        if (rc == 4) {
            // regs[2] corresponds to Register 4
            uint16_t reg4_val = regs[2];
            printf("Reg 4 current value: %u\n", reg4_val);

            if (reg4_val > 0) {
                printf("CONDITION MET: Register 4 is %u (> 0). Exiting.\n", reg4_val);
                success = 1;
                break;
            }
        } else {
            fprintf(stderr, "Read error: %s\n", modbus_strerror(errno));
        }

        sleep(1); // Wait 1 second before next poll
    }

    if (!success) {
        printf("Finished: Condition was not met within 30 seconds.\n");
    }

    modbus_close(ctx);
    modbus_free(ctx);
    return 0;
}
