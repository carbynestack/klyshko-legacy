/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "Tools/ezOptionParser.h"

#include "Math/Setup.h"
#include "Math/field_types.h"
#include "Math/gfp.hpp"
#include "Machines/SPDZ.hpp"
#include "Protocols/CowGearOptions.h"
#include "Protocols/CowGearShare.h"
#include "Protocols/CowGearPrep.hpp"
#include <fstream>
#include <assert.h>
#include <boost/filesystem.hpp>

#include <chrono>

template<class T, std::size_t ELEMENTS>
void generate_tuples(Preprocessing <T> &preprocessing, Dtype dtype, array <T, ELEMENTS> &tuple, string &subdir,
                     int tuple_count, int my_num) {
    char filename[2048];
    sprintf(filename, (subdir + "%s-%s-P%d").c_str(), DataPositions::dtype_names[dtype],
            (T::type_short()).c_str(), my_num);
    ofstream fout;
    fout.open(filename, ios::binary | ios::out);
    assert(fout.is_open());

    std::cout << "Generating " << tuple_count << " " << DataPositions::dtype_names[dtype] << " tuples to " << filename
              << std::endl;

    for (int i = 1; i <= tuple_count; ++i) {
        preprocessing.get(dtype, tuple.data());
        for (const T &ele: tuple) {
            ele.output(fout, false);
        }
    }

    std::cout << "Finished generating " << DataPositions::dtype_names[dtype] << std::endl;
    fout.close();
}

template<class T>
void generate_tuples(string tuple_type, int tuple_count, int my_num, int nplayers, int port, string playerfile) {
    std::string subdir = get_prep_sub_dir<T>(PREP_DIR, nplayers);
    boost::filesystem::path dir(subdir);
    if (!(boost::filesystem::exists(dir))) {
        std::cout << "Subdir doesn't Exists" << std::endl;
        if (boost::filesystem::create_directory(dir))
            std::cout << "....Successfully Created !" << std::endl;
    }
    Names N;
    N.init(my_num, port, playerfile, nplayers);
    PlainPlayer P(N);

    T::clear::template write_setup<T>(nplayers);

    // Initialize MAC key
    typename T::mac_key_type mac_key;
    T::read_or_generate_mac_key(PREP_DIR, P, mac_key);

    // Keep track of preprocessing usage (triples etc)
    DataPositions usage;
    usage.set_num_players(P.num_players());

    // Output protocol
    typename T::MAC_Check output(mac_key);

    // Initialize preprocessing
    CowGearPrep <T> preprocessing(0, usage);
    SubProcessor <T> processor(output, preprocessing, P);

    if (tuple_type == "bits") {
        array<T, 1> bit;
        generate_tuples<T, 1>(preprocessing, DATA_BIT, bit, subdir, tuple_count, my_num);
    } else if (tuple_type == "squares") {
        array<T, 2> square;
        generate_tuples<T, 2>(preprocessing, DATA_SQUARE, square, subdir, tuple_count, my_num);
    } else if (tuple_type == "inverses") {
        array<T, 2> inverse;
        generate_tuples<T, 2>(preprocessing, DATA_INVERSE, inverse, subdir, tuple_count, my_num);
    } else if (tuple_type == "triples") {
        array<T, 3> triple;
        generate_tuples<T, 3>(preprocessing, DATA_TRIPLE, triple, subdir, tuple_count, my_num);
    } else {
        std::cerr << "Tuple type not supported: " << tuple_type << std::endl;
        exit(EXIT_FAILURE);
    }
    output.Check(P);
}

int main(int argc, const char **argv) {
    ez::ezOptionParser opt;
    CowGearOptions(opt, argc, argv);
    opt.add(
            "2",                               // Default.
            0,                                 // Required?
            1,                                 // Number of args expected.
            0,                                 // Delimiter if expecting multiple args.
            "Number of parties (default: 2).", // Help description.
            "-N",                              // Flag token.
            "--parties"                        // Flag token.
    );
    opt.add(
            "",                                                  // Default.
            1,                                                   // Required?
            1,                                                   // Number of args expected.
            0,                                                   // Delimiter if expecting multiple args.
            "This player's number, starting with 0 (required).", // Help description.
            "-p",                                                // Flag token.
            "--player"                                           // Flag token.
    );
    opt.add(
            "",                                                     // Default.
            1,                                                      // Required?
            1,                                                      // Number of args expected.
            0,                                                      // Delimiter if expecting multiple args.
            "Playerfile containing host:port information per line", // Help description.
            "-pf",                                                  // Flag token.
            "--player-file"                                          // Flag token.
    );
    opt.add(
            "",
            1,                                                    // Required?
            1,                                                    // Number of args expected.
            0,                                                    // Delimiter if expecting multiple args.
            "Field to use. One of (gfp, gf2n)",                   // Help description.
            "-f",                                                 // Flag token.
            "--field"                                             // Flag token.
    );
    opt.add(
            "",                                                                 // Default.
            0,                                                                  // Required?
            1,                                                                  // Number of args expected.
            0,                                                                  // Delimiter if expecting multiple args.
            "Prime for GF(p) field",                                            // Help description.
            "-P",                                                               // Flag token.
            "--prime"                                                           // Flag token.
    );
    opt.add(
            "",
            1,                                                                          // Required?
            1,                                                                          // Number of args expected.
            0,                                                                          // Delimiter if expecting multiple args.
            "Tuple type to be generated. One of (bits, inverses, squares, triples)",    // Help description.
            "-tt",                                                                      // Flag token.
            "--tuple-type"                                                               // Flag token.
    );
    opt.add(
            "5000",                              // Default.
            0,                                   // Required?
            1,                                   // Number of args expected.
            0,                                   // Delimiter if expecting multiple args.
            "Base port number (default: 5000).", // Help description.
            "-P",                                // Flag token.
            "--port"                             // Flag token.
    );
    opt.add(
            "20000",                         // Default.
            0,                              // Required?
            1,                              // Number of args expected.
            0,                              // Delimiter if expecting multiple args.
            "Amount of tuples to generate", // Help description.
            "-tc",                          // Flag token.
            "--tuple-count"                 // Flag token.
    );
    string usage;
    opt.parse(argc, argv);
    if (!opt.isSet("-p")) {
        opt.getUsage(usage);
        cout << usage;
        exit(1);
    }

    int my_num, nplayers, port, tuple_count;
    string tuple_type, field, playerfile;
    opt.get("-p")->getInt(my_num);
    opt.get("-N")->getInt(nplayers);
    opt.get("-P")->getInt(port);
    opt.get("-tt")->getString(tuple_type);
    opt.get("-f")->getString(field);
    opt.get("-tc")->getInt(tuple_count);
    opt.get("-pf")->getString(playerfile);

    if (mkdir_p(PREP_DIR) == -1) {
        throw runtime_error(
                (string) "cannot use " + PREP_DIR + " (set another PREP_DIR in CONFIG if needed)");
    }

    if (field == "gfp") {
        // First template argument is counter. convention is 0 for online, 1 for offline phase
        // Second template argument is number of limbs (128 bit prime -> 2)
        typedef CowGearShare <gfp_<1, 2>> T;
        if (opt.isSet("--prime")) {
            string p;
            opt.get("--prime")->getString(p);
            T::clear::init_field(p, true);
        } else {
            cerr << "ERROR: 'prime' must be provided when field is 'gfp'" << std::endl;
            opt.getUsage(usage);
            cout << usage;
            return 1;
        }
        generate_tuples<T>(tuple_type, tuple_count, my_num, nplayers, port, playerfile);
    } else if (field == "gf2n") {
        typedef CowGearShare <gf2n_short> T;
        gf2n_short::init_field(40);
        generate_tuples<T>(tuple_type, tuple_count, my_num, nplayers, port, playerfile);
    } else {
        cerr << "ERROR: 'field' must either 'gfp' or 'gf2n'" << std::endl;
        opt.getUsage(usage);
        cout << usage;
        return 1;
    }

}
