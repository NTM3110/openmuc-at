#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <modbus/modbus.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <serial_port>\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1\n", argv[0]);
        return 1;
    }

    const char *port = argv[1];
    const int min_slave_id = 1;
    const int max_slave_id = 247;
    const int first_register = 0x0000;

    modbus_t *ctx = modbus_new_rtu(port, 9600, 'N', 8, 1);
    if (!ctx) {
        fprintf(stderr, "Unable to create libmodbus context\n");
        return 1;
    }

    modbus_set_debug(ctx, 1);
    modbus_set_response_timeout(ctx, 0, 500000);

    if (modbus_connect(ctx) == -1) {
        fprintf(stderr, "Connection failed: %s\n", modbus_strerror(errno));
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> SCANNING TA MODULE ADDRESS <<<\n");
    printf("========================================\n");
    printf("Scanning Modbus IDs %d-%d by reading register 0x%04X.\n",
           min_slave_id, max_slave_id, first_register);
    printf("Assumption: only one TA module is connected on the bus.\n\n");

    for (int slave_id = min_slave_id; slave_id <= max_slave_id; slave_id++) {
        uint16_t read_value = 0;

        modbus_set_slave(ctx, slave_id);
        int rc = modbus_read_registers(ctx, first_register, 1, &read_value);

        if (rc == 1) {
            printf("\n========================================\n");
            printf(">>> MODULE ADDRESS DISCOVERED <<<\n");
            printf("========================================\n");
            printf("Discovered Modbus address: %d\n", slave_id);
            printf("[ID %d] Register 0x%04X responded with 0x%04X (%u)\n",
                   slave_id, first_register, read_value, read_value);

            modbus_close(ctx);
            modbus_free(ctx);
            return 0;
        }

        printf("[ID %d] No response: %s\n", slave_id, modbus_strerror(errno));
        usleep(100000);
    }

    printf("\nNo module responded while scanning IDs %d-%d.\n",
           min_slave_id, max_slave_id);

    modbus_close(ctx);
    modbus_free(ctx);
    return 1;
}
