//
// Created by Aanswer_Dev on 2025/8/5.
//

#include "Tag.h"
#include <iostream>

Tag::Tag() {
    std::cout << "Tag created" << std::endl;
}

Tag::~Tag() {
    std::cout << "Tag destroyed" << std::endl;
}

void Tag::doSomething() {
    std::cout << "Tag is doing something!" << std::endl;
}
