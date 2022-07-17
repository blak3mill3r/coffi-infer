#include <clang-c/Index.h>
#include <iostream>

int main(int argc, char** argv){
  CXChildVisitResult x;
  std::cout << "Size = " << sizeof(x) << std::endl;
  std::cout << "CXChildVisit_Break     = " << CXChildVisit_Break    << std::endl;
  std::cout << "CXChildVisit_Continue  = " << CXChildVisit_Continue << std::endl;
  std::cout << "CXChildVisit_Recurse   = " << CXChildVisit_Recurse  << std::endl;


  std::cout << "CXChildVisit_Recurse   = " << CXChildVisit_Recurse  << std::endl;
  return 0;
}
