#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <stdint.h>
#include <modbus/modbus.h>

static int check_slave_response(modbus_t *ctx, int slave_id, int timeout_seconds) {
    const int first_register = 0x0000;
    time_t start_poll = time(NULL);

    modbus_set_slave(ctx, slave_id);

    while (difftime(time(NULL), start_poll) < timeout_seconds) {
        uint16_t read_value = 0;
        int rc = modbus_read_registers(ctx, first_register, 1, &read_value);

        if (rc == 1) {
            printf("[ID %d] Verified response from register 0x%04X: 0x%04X (%u)\n",
                   slave_id, first_register, read_value, read_value);
            return 1;
        }

        printf("[ID %d] Verification read failed: %s\n",
               slave_id, modbus_strerror(errno));
        sleep(1);
    }

    return 0;
}

static int read_register_once(modbus_t *ctx, int slave_id, int register_address, uint16_t *read_value) {
    modbus_set_slave(ctx, slave_id);
    return modbus_read_registers(ctx, register_address, 1, read_value);
}

static int write_register_with_retries(modbus_t *ctx, int slave_id, int register_address, uint16_t value, int attempts) {
    modbus_set_slave(ctx, slave_id);

    for (int attempt = 1; attempt <= attempts; attempt++) {
        printf("[ID %d] Write attempt %d/%d: register 0x%04X = 0x%04X (%u)\n",
               slave_id, attempt, attempts, register_address, value, value);
        modbus_flush(ctx);

        if (modbus_write_register(ctx, register_address, value) != -1) {
            printf("[ID %d] Write attempt %d returned normal confirmation.\n",
                   slave_id, attempt);
            return 1;
        }

        fprintf(stderr, "[ID %d] Write attempt %d failed: %s\n",
                slave_id, attempt, modbus_strerror(errno));

        if (attempt < attempts) {
            sleep(1);
        }
    }

    return 0;
}

static int verify_address_change(modbus_t *ctx, int old_slave_id, int new_slave_id) {
    printf("\n========================================\n");
    printf(">>> VERIFYING ADDRESS CHANGE <<<\n");
    printf("========================================\n");
    printf("Checking whether module moved from ID %d to ID %d...\n",
           old_slave_id, new_slave_id);

    sleep(1);
    modbus_flush(ctx);

    if (check_slave_response(ctx, new_slave_id, 10)) {
        printf("Address change verified: new ID %d is responding.\n", new_slave_id);
        return 1;
    }

    printf("New ID %d did not respond. Checking old ID %d...\n",
           new_slave_id, old_slave_id);

    if (check_slave_response(ctx, old_slave_id, 3)) {
        printf("ADDRESS CHANGE DID NOT APPLY: module is still responding at old ID %d.\n",
               old_slave_id);
    } else {
        printf("Neither old ID %d nor new ID %d responded during verification.\n",
               old_slave_id, new_slave_id);
    }

    return 0;
}

static int activate_ir_reading(modbus_t *ctx, int slave_id) {
    const int ir_activation_register = 0x000C;
    const uint16_t ir_activation_value = 0xF0F0;

    printf("\n========================================\n");
    printf(">>> IR ACTIVATION PHASE FOR ID: %d <<<\n", slave_id);
    printf("========================================\n");

    modbus_set_slave(ctx, slave_id);

    // Same IR command flow as testIR-multiple.c: one activation write, then poll.
    if (modbus_write_register(ctx, ir_activation_register, ir_activation_value) == -1) {
        fprintf(stderr, "[ID %d] Write failed: %s\n", slave_id, modbus_strerror(errno));
    } else {
        printf("[ID %d] Write 0x%04X successful.\n", slave_id, ir_activation_value);
    }

    time_t start_poll = time(NULL);
    int success = 0;

    while (difftime(time(NULL), start_poll) < 30) {
        uint16_t regs[4] = {0};
        int rc = modbus_read_registers(ctx, 1, 4, regs);

        if (rc == 4) {
            uint16_t reg4_val = regs[2];
            printf("[ID %d] Polling... Reg 4: %u\n", slave_id, reg4_val);

            if (reg4_val > 0) {
                printf("[ID %d] CONDITION MET (> 0). IR activation/read complete.\n",
                       slave_id);
                success = 1;
                break;
            }
        }

        sleep(1);
    }

    if (!success) {
        printf("[ID %d] 30s Timeout reached.\n", slave_id);
    }

    return success;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <serial_port> <new_slave_id>\n", argv[0]);
        fprintf(stderr, "Example: %s /dev/ttyS1 40\n", argv[0]);
        return 1;
    }

    const char *port = argv[1];
    const int current_slave_id = 1;
    const int slave_id_register = 0x000E;

    errno = 0;
    char *endptr = NULL;
    unsigned long parsed_value = strtoul(argv[2], &endptr, 0);
    if (errno != 0 || endptr == argv[2] || *endptr != '\0' || parsed_value < 1 || parsed_value > 247) {
        fprintf(stderr, "Invalid slave ID '%s'. Use a Modbus slave ID from 1 to 247, decimal or hex.\n", argv[2]);
        return 1;
    }

    uint16_t new_slave_id = (uint16_t) parsed_value;

    // Initialize Modbus RTU: 9600 baud, No parity, 8 data bits, 1 stop bit
    modbus_t *ctx = modbus_new_rtu(port, 9600, 'N', 8, 1);
    if (!ctx) {
        fprintf(stderr, "Unable to create libmodbus context\n");
        return 1;
    }

    // Enable debug to see the raw hex frames
    modbus_set_debug(ctx, 1);

    // Match the working IR test timing.
    modbus_set_response_timeout(ctx, 1, 0);

    if (modbus_connect(ctx) == -1) {
        fprintf(stderr, "Connection failed: %s\n", modbus_strerror(errno));
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> VERIFYING OLD ADDRESS <<<\n");
    printf("========================================\n");
    printf("Expected current/default module address: %d\n", current_slave_id);
    if (!check_slave_response(ctx, current_slave_id, 5)) {
        fprintf(stderr, "Old address %d did not respond. Address change aborted.\n",
                current_slave_id);
        modbus_close(ctx);
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> ADDRESS CHANGE PHASE <<<\n");
    printf("========================================\n");
    printf("Changing module address from old ID %d to new ID %u.\n",
           current_slave_id, new_slave_id);
    modbus_set_slave(ctx, current_slave_id);

    printf("Writing new address with Function 06: register 0x%04X = %u.\n",
           slave_id_register, new_slave_id);
    if (!write_register_with_retries(ctx, current_slave_id, slave_id_register, new_slave_id, 3)) {
        printf("Address register write did not return normal confirmation after retries.\n");
        printf("Continuing to verify new ID because the module may still have processed one write.\n");
    } else {
        printf("Address register write returned success.\n");
    }

    if (!verify_address_change(ctx, current_slave_id, new_slave_id)) {
        uint16_t old_id_register_value = 0;

        printf("New ID did not respond. Trying diagnostic read of address register on old ID %d...\n",
               current_slave_id);
        if (read_register_once(ctx, current_slave_id, slave_id_register, &old_id_register_value) == 1) {
            printf("[ID %d] Address register 0x%04X still reads 0x%04X (%u).\n",
                   current_slave_id, slave_id_register, old_id_register_value, old_id_register_value);
        } else {
            fprintf(stderr, "[ID %d] Diagnostic read of address register failed: %s\n",
                    current_slave_id, modbus_strerror(errno));
        }

        fprintf(stderr, "Address change failed. IR activation will not run.\n");
        modbus_close(ctx);
        modbus_free(ctx);
        return 1;
    }

    printf("\n========================================\n");
    printf(">>> ADDRESS CHANGE WRITE SUCCESSFUL <<<\n");
    printf("========================================\n");
    printf("Switching to new Modbus ID %u. Resting 20 seconds before IR activation/read process.\n",
           new_slave_id);
    sleep(20);

    int ir_success = activate_ir_reading(ctx, new_slave_id);

    modbus_close(ctx);
    modbus_free(ctx);
    return ir_success ? 0 : 1;
}
