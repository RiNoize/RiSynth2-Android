CXX ?= clang++
CXXFLAGS ?= -std=c++20 -Wall -Wextra -Werror -O1 -g

BUILD_DIR := build
RUNNER := $(BUILD_DIR)/engine_core_tests
SRCS := $(wildcard *.cpp)
HDRS := $(wildcard *.hpp) $(wildcard ../EngineCore/*.hpp)

.PHONY: test clean

test: $(RUNNER)
	./$(RUNNER)

$(RUNNER): $(SRCS) $(HDRS) legacy_fixture.txt
	@mkdir -p $(BUILD_DIR)
	$(CXX) $(CXXFLAGS) -DOXO_TESTS_DIR='"$(CURDIR)"' $(SRCS) -o $@ -lz

clean:
	rm -rf $(BUILD_DIR)
