# Builds crimson-unpack for the PS5 with the ps5-payload-sdk (PS5_PAYLOAD_SDK points at it), and for Linux (the tests).
ifdef PS5_PAYLOAD_SDK
    include $(PS5_PAYLOAD_SDK)/toolchain/prospero.mk
endif

CFLAGS := -Wall -Werror -O2

all: crimson-unpack.elf

crimson-unpack.elf: unpack.c
	$(CC) $(CFLAGS) -o $@ $^

# Writes anywhere, so the tests can use a temporary folder
unpack-linux: unpack.c
	cc $(CFLAGS) -DUNPACK_ANY_DEST -o $@ $^

test: unpack-linux
	python3 test_unpack.py ./unpack-linux

clean:
	rm -f crimson-unpack.elf unpack-linux
